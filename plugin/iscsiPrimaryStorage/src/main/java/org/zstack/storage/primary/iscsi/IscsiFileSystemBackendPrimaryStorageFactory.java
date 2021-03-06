package org.zstack.storage.primary.iscsi;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.ansible.AnsibleFacade;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.header.Component;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.HostInventory;
import org.zstack.header.host.HypervisorType;
import org.zstack.header.image.ImagePlatform;
import org.zstack.header.storage.backup.BackupStorageType;
import org.zstack.header.storage.primary.*;
import org.zstack.header.vm.*;
import org.zstack.header.volume.VolumeInventory;
import org.zstack.kvm.KVMAgentCommands.AttachDataVolumeCmd;
import org.zstack.kvm.KVMAgentCommands.DetachDataVolumeCmd;
import org.zstack.kvm.KVMAgentCommands.StartVmCmd;
import org.zstack.kvm.KVMAgentCommands.VolumeTO;
import org.zstack.kvm.*;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.function.Function;

import javax.persistence.Tuple;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by frank on 4/19/2015.
 */
public class IscsiFileSystemBackendPrimaryStorageFactory implements PrimaryStorageFactory, KVMStartVmExtensionPoint,
        KVMAttachVolumeExtensionPoint, KVMDetachVolumeExtensionPoint, Component {
    public static final PrimaryStorageType type = new PrimaryStorageType(IscsiPrimaryStorageConstants.ISCSI_FILE_SYSTEM_BACKEND_PRIMARY_STORAGE_TYPE);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private CloudBus bus;
    @Autowired
    private AnsibleFacade asf;

    private Map<BackupStorageType, Map<HypervisorType, IscsiFileSystemBackendPrimaryToBackupStorageMediator>> mediators = new HashMap<BackupStorageType, Map<HypervisorType, IscsiFileSystemBackendPrimaryToBackupStorageMediator>>();

    @Override
    public PrimaryStorageType getPrimaryStorageType() {
        return type;
    }

    IscsiFileSystemBackendPrimaryToBackupStorageMediator getPrimaryToBackupStorageMediator(BackupStorageType bsType, HypervisorType hvType) {
        Map<HypervisorType, IscsiFileSystemBackendPrimaryToBackupStorageMediator> mediatorMap = mediators.get(bsType);
        if (mediatorMap == null) {
            throw new CloudRuntimeException(String.format("primary storage[type:%s] wont have mediator supporting backup storage[type:%s]", type, bsType));
        }
        IscsiFileSystemBackendPrimaryToBackupStorageMediator mediator = mediatorMap.get(hvType);
        if (mediator == null) {
            throw new CloudRuntimeException(String.format("PrimaryToBackupStorageMediator[primary storage type: %s, backup storage type: %s] doesn't have backend supporting hypervisor type[%s]" , type, bsType, hvType));
        }
        return mediator;
    }

    private void populateExtensions() {
        for (IscsiFileSystemBackendPrimaryToBackupStorageMediator extp : pluginRgty.getExtensionList(IscsiFileSystemBackendPrimaryToBackupStorageMediator.class)) {
            if (extp.getSupportedPrimaryStorageType().equals(type)) {
                Map<HypervisorType, IscsiFileSystemBackendPrimaryToBackupStorageMediator> map = mediators.get(extp.getSupportedBackupStorageType());
                if (map == null) {
                    map = new HashMap<HypervisorType, IscsiFileSystemBackendPrimaryToBackupStorageMediator>(1);
                }
                for (HypervisorType hvType : extp.getSupportedHypervisorTypes()) {
                    map.put(hvType, extp);
                }
                mediators.put(extp.getSupportedBackupStorageType(), map);
            }
        }
    }



    @Override
    public PrimaryStorageInventory createPrimaryStorage(final PrimaryStorageVO vo, final APIAddPrimaryStorageMsg msg) {
        IscsiFileSystemBackendPrimaryStorageVO ivo = new IscsiFileSystemBackendPrimaryStorageVO(vo);
        APIAddIscsiFileSystemBackendPrimaryStorageMsg amsg = (APIAddIscsiFileSystemBackendPrimaryStorageMsg) msg;
        ivo.setFilesystemType(amsg.getFilesystemType());
        ivo.setHostname(amsg.getHostname());
        ivo.setSshPassword(amsg.getSshPassword());
        ivo.setSshUsername(amsg.getSshUsername());
        ivo.setChapPassword(amsg.getChapPassword());
        ivo.setChapUsername(amsg.getChapUsername());
        ivo.setMountPath(ivo.getUrl());
        ivo = dbf.persistAndRefresh(ivo);

        return IscsiFileSystemBackendPrimaryStorageInventory.valueOf(ivo);
    }

    @Override
    public PrimaryStorage getPrimaryStorage(PrimaryStorageVO vo) {
        return new IscsiFilesystemBackendPrimaryStorage(dbf.findByUuid(vo.getUuid(), IscsiFileSystemBackendPrimaryStorageVO.class));
    }

    @Override
    public PrimaryStorageInventory getInventory(String uuid) {
        IscsiFileSystemBackendPrimaryStorageVO vo = dbf.findByUuid(uuid, IscsiFileSystemBackendPrimaryStorageVO.class);
        return IscsiFileSystemBackendPrimaryStorageInventory.valueOf(vo);
    }

    @Override
    public boolean start() {
        populateExtensions();

        if (!CoreGlobalProperty.UNIT_TEST_ON) {
            asf.deployModule(IscsiFileSystemBackendPrimaryStorageGlobalProperty.ANSIBLE_MODULE_PATH, IscsiFileSystemBackendPrimaryStorageGlobalProperty.ANSIBLE_PLAYBOOK_NAME);
        }

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    private KVMIscsiVolumeTO convertToIscsiToIfNeed(HostInventory host, VolumeInventory vol, VolumeTO to) {
        if (!VolumeTO.ISCSI.equals(to.getDeviceType())) {
            return null;
        }

        SimpleQuery<IscsiFileSystemBackendPrimaryStorageVO> q  = dbf.createQuery(IscsiFileSystemBackendPrimaryStorageVO.class);
        q.select(IscsiFileSystemBackendPrimaryStorageVO_.chapUsername, IscsiFileSystemBackendPrimaryStorageVO_.chapPassword);
        q.add(IscsiFileSystemBackendPrimaryStorageVO_.uuid, Op.EQ, vol.getPrimaryStorageUuid());
        Tuple t = q.findTuple();
        if (t == null) {
            return null;
        }

        String chapUsername = t.get(0, String.class);
        String chapPassword = t.get(1, String.class);

        KVMIscsiVolumeTO kto = new KVMIscsiVolumeTO(to);
        kto.setChapUsername(chapUsername);
        kto.setChapPassword(chapPassword);
        IscsiVolumePath path = new IscsiVolumePath(to.getInstallPath());
        kto.setInstallPath(path.disassemble().assembleIscsiPath());

        DebugUtils.Assert(vol.getVmInstanceUuid() != null, String.format("vmInstanceUuid of volume[uuid:%s] is null", vol.getUuid()));
        SimpleQuery<VmInstanceVO> vmq = dbf.createQuery(VmInstanceVO.class);
        vmq.select(VmInstanceVO_.platform);
        vmq.add(VmInstanceVO_.uuid, Op.EQ, vol.getVmInstanceUuid());
        String platform = vmq.findValue();

        boolean useVirtio = KVMSystemTags.VIRTIO_SCSI.hasTag(host.getUuid())
                && (ImagePlatform.Paravirtualization.toString().equals(platform) || ImagePlatform.Linux.toString().equals(platform));
        kto.setUseVirtio(useVirtio);

        return kto;
    }

    private KVMIscsiVolumeTO convertToIscsiToIfNeed(HostInventory host, List<VolumeInventory> srcVols, final VolumeTO to) {
        if (!VolumeTO.ISCSI.equals(to.getDeviceType())) {
            return null;
        }

        VolumeInventory vol = CollectionUtils.find(srcVols, new Function<VolumeInventory, VolumeInventory>() {
            @Override
            public VolumeInventory call(VolumeInventory arg) {
                return arg.getDeviceId() == to.getDeviceId() ? arg : null;
            }
        });

        return convertToIscsiToIfNeed(host, vol, to);
    }

    @Override
    public void beforeStartVmOnKvm(KVMHostInventory host, VmInstanceSpec spec, StartVmCmd cmd) throws KVMException {
        List<VolumeTO> dataTOs = new ArrayList<VolumeTO>(cmd.getDataVolumes().size());
        for (final VolumeTO to : cmd.getDataVolumes()) {
            KVMIscsiVolumeTO kto = convertToIscsiToIfNeed(host, spec.getDestDataVolumes(), to);
            dataTOs.add(kto == null ? to : kto);
        }

        cmd.setDataVolumes(dataTOs);

        KVMIscsiVolumeTO rootKto = convertToIscsiToIfNeed(host, spec.getDestRootVolume(), cmd.getRootVolume());
        if (rootKto != null) {
            cmd.setRootVolume(rootKto);
        }
    }

    @Override
    public void startVmOnKvmSuccess(KVMHostInventory host, VmInstanceSpec spec) {

    }

    @Override
    public void startVmOnKvmFailed(KVMHostInventory host, VmInstanceSpec spec, ErrorCode err) {

    }

    @Override
    public void beforeAttachVolume(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, AttachDataVolumeCmd cmd) {
        KVMIscsiVolumeTO kto = convertToIscsiToIfNeed(host, volume, cmd.getVolume());
        if (kto != null) {
            cmd.setVolume(kto);
        }
    }

    @Override
    public void afterAttachVolume(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, AttachDataVolumeCmd cmd) {

    }

    @Override
    public void attachVolumeFailed(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, AttachDataVolumeCmd cmd, ErrorCode err) {

    }

    @Override
    public void beforeDetachVolume(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, DetachDataVolumeCmd cmd) {
        KVMIscsiVolumeTO kto = convertToIscsiToIfNeed(host, volume, cmd.getVolume());
        if (kto != null) {
            cmd.setVolume(kto);
        }
    }

    @Override
    public void afterDetachVolume(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, DetachDataVolumeCmd cmd) {

    }

    @Override
    public void detachVolumeFailed(KVMHostInventory host, VmInstanceInventory vm, VolumeInventory volume, DetachDataVolumeCmd cmd, ErrorCode err) {

    }
}
