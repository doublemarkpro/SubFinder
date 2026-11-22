package cn.nastv.subtitles;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.Interface;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1;
import com.rapid7.client.dcerpc.transport.RPCTransport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Blocking SMB2/3 operations within one authenticated NAS session. */
public final class NasStore implements Closeable {
    private final SMBClient client;
    private final Session session;
    private DiskShare share;

    public static final class Entry {
        private static final java.util.Set<String> VIDEO = new java.util.HashSet<>(Arrays.asList(
                "mkv","mp4","m4v","avi","mov","ts","m2ts","mts","mpg","mpeg","wmv","webm","iso","vob",
                "flv","rmvb","m2v","3gp","asf","ogm","divx","rm","f4v"));
        public final String name;
        public final String path;
        public final boolean directory;
        public final long size;

        private Entry(String name, String path, boolean directory, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }

        boolean isVideo() {
            int dot = name.lastIndexOf('.');
            return !directory && dot >= 0 && VIDEO.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
    }

    private NasStore(SMBClient client, Session session) {
        this.client = client;
        this.session = session;
    }

    public static NasStore connect(String host, int port, String shareName, String domain,
                                   String username, String password) throws IOException {
        NasStore store = login(host, port, domain, username, password);
        try {
            store.selectShare(shareName);
            return store;
        } catch (IOException | RuntimeException e) {
            store.close();
            throw e;
        }
    }

    public static NasStore login(String host, int port, String domain,
                                 String username, String password) throws IOException {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IOException("请检查 NAS 主机地址和端口。");
        }
        SmbConfig config = SmbConfig.builder()
                .withAuthenticators(new NtlmAuthenticator.Factory())
                .withTimeout(20, TimeUnit.SECONDS)
                .build();
        SMBClient client = new SMBClient(config);
        char[] secret = password == null ? new char[0] : password.toCharArray();
        try {
            Connection connection = client.connect(host.trim(), port);
            AuthenticationContext auth = new AuthenticationContext(
                    username == null ? "" : username, secret, domain == null ? "" : domain);
            return new NasStore(client, connection.authenticate(auth));
        } catch (IOException | RuntimeException e) {
            client.close();
            throw explain("无法连接 NAS，请检查地址、端口和 SMB 服务。", e);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** Enumerate published file shares with SRVSVC level 1, using the existing SMB login. */
    public List<String> listShares() throws IOException {
        List<String> names = new ArrayList<>();
        try {
            Share ipc = session.connectShare("IPC$");
            if (!(ipc instanceof PipeShare)) throw new IOException("服务器未提供共享列表服务。");
            PipeShare pipes = (PipeShare) ipc;
            try (NamedPipe pipe = pipes.open("srvsvc", SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OPEN, null)) {
                RPCTransport rpc = new ShareListTransport(pipe);
                rpc.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2);
                for (NetShareInfo1 info : new ServerService(rpc).getShares1()) {
                    String name = info.getNetName();
                    // Type 0 is a disk share. Skip special (administrative) and hidden shares.
                    if ((info.getType() & 0x800000ff) == 0 && validName(name) && !name.endsWith("$")) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("已登录 NAS，但无法列出共享。可以直接输入已知共享路径。", e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Open a disk share. A failed selection preserves the previously selected share. */
    public void selectShare(String name) throws IOException {
        if (!validName(name)) throw new IOException("请输入一个 SMB 共享名称，例如 Media。");
        try {
            Share connected = session.connectShare(name);
            if (!(connected instanceof DiskShare)) throw new IOException("该共享不是文件夹。");
            share = (DiskShare) connected;
        } catch (RuntimeException e) {
            throw explain("无法打开共享，请检查账户的读取权限。", e);
        }
    }

    /** A view of this login pinned to one share, independent of the library browser's selection. */
    NasStore viewOfShare(String name) throws IOException {
        NasStore view = new NasStore(null, session);
        view.selectShare(name);
        return view;
    }

    void copyArchive(Entry entry, OutputStream target, long limit) throws IOException {
        if (entry.directory || entry.size < 1 || entry.size > limit) throw new IOException("字幕包为空或超过大小限制。");
        try (File file = share.openFile(smbPath(entry.path, false), EnumSet.of(AccessMask.FILE_READ_DATA), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null); InputStream in = file.getInputStream()) {
            long total = 0;
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit || total > entry.size) throw new IOException("字幕包大小发生变化，请重新选择。");
                target.write(buffer, 0, count);
            }
            if (total != entry.size) throw new IOException("字幕包读取不完整，请重新选择。");
        } catch (RuntimeException e) {
            throw explain("无法读取字幕包，请检查 NAS 连接和文件夹权限。", e);
        }
    }

    /** Use SMBJ's current pipe API and release each pipe as soon as enumeration finishes. */
    private static final class ShareListTransport extends RPCTransport {
        private final NamedPipe pipe;
        ShareListTransport(NamedPipe pipe) { this.pipe = pipe; }
        @Override public int transact(byte[] request, byte[] response) { return pipe.transact(request, response); }
        @Override public int read(byte[] response) { return pipe.read(response); }
        @Override public void write(byte[] request) throws IOException {
            if (pipe.write(request) != request.length) throw new IOException("共享列表请求发送不完整。");
        }
    }

    public List<Entry> list(String directory) throws IOException {
        if (share == null) throw new IOException("请先选择共享文件夹。");
        String smbDirectory = smbPath(directory, true);
        List<Entry> entries = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation item : share.list(smbDirectory)) {
                String name = item.getFileName();
                if (!validName(name)) continue;
                boolean folder = (item.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                entries.add(new Entry(name, child(directory, name), folder, item.getEndOfFile()));
            }
        } catch (RuntimeException e) {
            throw explain("无法读取文件夹，请检查 NAS 连接和读取权限。", e);
        }
        Collections.sort(entries, (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
        return entries;
    }

    /** Downloads supply bytes only. The selected video's path controls the directory and base name. */
    public String saveSubtitle(String videoPath, String languageTag, String extension, byte[] data) throws IOException {
        return saveSubtitle(videoPath, languageTag, extension, data, true);
    }

    /** Return null if another writer has already published the batch target. */
    String saveBatchSubtitle(String videoPath, String extension, byte[] data) throws IOException {
        return saveSubtitle(videoPath, "zh-CN", extension, data, false);
    }

    private String saveSubtitle(String videoPath, String languageTag, String extension, byte[] data,
                                boolean keepCopies) throws IOException {
        if (share == null) throw new IOException("请先选择共享文件夹。");
        smbPath(videoPath, false);
        if (languageTag == null || !languageTag.matches("[A-Za-z0-9]{1,8}(-[A-Za-z0-9]{1,8})*")) {
            throw new IOException("字幕语言代码无效。");
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);
        if (!ext.matches("srt|ass|ssa|vtt|sub")) throw new IOException("不支持此字幕格式。");
        if (data == null || data.length == 0) throw new IOException("下载的字幕是空文件。");

        int slash = videoPath.lastIndexOf('/');
        String folder = slash < 0 ? "" : videoPath.substring(0, slash);
        String fileName = videoPath.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        String temp = child(folder, ".nastv-" + UUID.randomUUID() + ".tmp");
        try (File stage = share.openFile(smbPath(temp, false),
                EnumSet.of(AccessMask.FILE_WRITE_DATA, AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null)) {
            try {
                if (stage.write(data, 0) != data.length) {
                    throw new IOException("NAS 未能写入完整的字幕文件。");
                }
                stage.flush();
                for (int copy = 1; copy <= 999; copy++) {
                    String suffix = copy == 1 ? "" : "." + copy;
                    String target = child(folder, stem + suffix + "." + languageTag + "." + ext);
                    try {
                        // The server checks for collisions under its rename lock, including concurrent saves.
                        stage.rename(smbPath(target, false), false);
                        return target;
                    } catch (SMBApiException e) {
                        if (e.getStatus() != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e;
                        if (!keepCopies) {
                            stage.deleteOnClose();
                            return null;
                        }
                    }
                }
                throw new IOException("同名字幕过多，请先整理影片所在的文件夹。");
            } catch (IOException | RuntimeException failure) {
                try {
                    stage.deleteOnClose();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (RuntimeException e) {
            throw explain("无法保存字幕，请检查 NAS 连接和文件夹的写入权限。", e);
        }
    }

    @Override public void close() {
        if (client != null) client.close();
    }

    private static String child(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static boolean validName(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) return false;
        }
        return true;
    }

    private static String smbPath(String path, boolean allowRoot) throws IOException {
        if (allowRoot && "".equals(path)) return "";
        if (path == null) throw new IOException("文件路径为空。");
        for (String part : path.split("/", -1)) {
            if (!validName(part)) throw new IOException("共享中的文件路径无效。");
        }
        return path.replace('/', '\\');
    }

    private static IOException explain(String fallback, Exception problem) {
        if (problem instanceof SMBApiException) {
            SMBApiException smb = (SMBApiException) problem;
            String message = fallback;
            switch (smb.getStatus()) {
                case STATUS_LOGON_FAILURE:
                    message = "NAS 登录失败，请检查用户名、密码和域名。";
                    break;
                case STATUS_BAD_NETWORK_NAME:
                    message = "找不到此共享，请核对 NAS 上显示的共享名称。";
                    break;
                case STATUS_ACCESS_DENIED:
                    message = "NAS 拒绝了操作，请检查此账号的共享权限。";
                    break;
                case STATUS_DISK_FULL:
                    message = "NAS 存储空间已满。";
                    break;
                default:
                    break;
            }
            return new IOException(message + " (" + smb.getStatus().name() + ")", problem);
        }
        return new IOException(fallback, problem);
    }
}
