package cn.nastv.subtitles;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class ShareAddress {
    final String host, share, folder;
    final int port;

    private ShareAddress(String host, int port, String share, String folder) {
        this.host = host;
        this.port = port;
        this.share = share;
        this.folder = folder;
    }

    static ShareAddress parse(String address, String shareField) {
        String raw = address.trim().replace('\\', '/');
        if (raw.startsWith("//")) raw = "smb:" + raw;
        if (!raw.contains("://")) raw = "smb://" + raw;
        URI uri;
        try {
            uri = new URI(raw.replace(" ", "%20"));
        } catch (Exception ex) {
            throw new IllegalArgumentException("请检查 NAS 地址，可以填写 IP 地址或主机名。");
        }
        String host = uri.getHost();
        if (host == null || !"smb".equalsIgnoreCase(uri.getScheme()) ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("请输入 NAS 的 IP 或主机名，账号请填写在用户名栏。");
        }
        int port = uri.getPort() == -1 ? 445 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应为 1 至 65535。");
        String fromAddress = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/+", "");
        String field = shareField.trim().replace('\\', '/').replaceFirst("^/+", "");
        if (!fromAddress.isEmpty() && !field.isEmpty() && !fromAddress.equals(field)) {
            throw new IllegalArgumentException("地址中已含共享路径。请清空共享文件夹栏，或只在地址栏填写主机名。");
        }
        String path = fromAddress.isEmpty() ? field : fromAddress;
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/+")) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..") || part.matches(".*[\\x00-\\x1f:*?\"<>|].*"))
                throw new IllegalArgumentException("请填写有效的共享文件夹名称。");
            parts.add(part);
        }
        if (parts.isEmpty()) return new ShareAddress(host, port, "", "");
        String share = parts.remove(0);
        return new ShareAddress(host, port, share, String.join("/", parts));
    }

    String endpoint() {
        String label = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return port == 445 ? label : label + ":" + port;
    }
}
