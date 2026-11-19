package org.abdullahcxd.paperhive.host.instance;

import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Just enough of the Minecraft protocol to ask a server's status and get an offline-mode login accepted. */
final class MinecraftClient {

    private MinecraftClient() {}

    /** The status response JSON (version, motd, players). */
    static @NonNull String status(int port) throws IOException {
        try (Socket socket = connect(port)) {
            OutputStream out = socket.getOutputStream();
            handshake(out, -1, port, 1);
            packet(out, body(0x00)); // status request: no fields
            DataInputStream in = new DataInputStream(socket.getInputStream());
            readVarInt(in); // length
            if (readVarInt(in) != 0) throw new IOException("not a status response");
            return readString(in);
        }
    }

    static int protocol(String status) {
        Matcher m = Pattern.compile("\"protocol\"\\s*:\\s*(\\d+)").matcher(status);
        if (!m.find()) throw new IllegalArgumentException("no protocol in " + status);
        return Integer.parseInt(m.group(1));
    }

    /** Logs in as an offline player and disconnects once the server accepts; returns the name the server confirmed. */
    static @NonNull String login(int port, String name) throws IOException {
        int protocol = protocol(status(port));
        try (Socket socket = connect(port)) {
            OutputStream out = socket.getOutputStream();
            handshake(out, protocol, port, 2);
            ByteArrayOutputStream start = body(0x00);
            DataOutputStream d = new DataOutputStream(start);
            writeString(d, name);
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            d.writeLong(id.getMostSignificantBits());
            d.writeLong(id.getLeastSignificantBits());
            packet(out, start);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            boolean compressed = false;
            while (true) {
                readVarInt(in); // length
                // shortcut: after Set Compression only small (uncompressed, data length 0) packets are expected
                if (compressed && readVarInt(in) != 0) throw new IOException("unexpected compressed login packet");
                int packetId = readVarInt(in);
                switch (packetId) {
                    case 0x00 -> throw new IOException("login refused: " + readString(in));
                    case 0x01 -> throw new IOException("server wants encryption (online-mode is on)");
                    case 0x03 -> {
                        readVarInt(in); // threshold
                        compressed = true;
                    }
                    case 0x02 -> {
                        in.readLong();
                        in.readLong();
                        return readString(in);
                    }
                    default -> throw new IOException("unexpected login packet 0x" + Integer.toHexString(packetId));
                }
            }
        }
    }

    private static @NonNull Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(15_000);
        return socket;
    }

    private static void handshake(OutputStream out, int protocol, int port, int nextState) throws IOException {
        ByteArrayOutputStream body = body(0x00);
        DataOutputStream d = new DataOutputStream(body);
        writeVarInt(d, protocol);
        writeString(d, "127.0.0.1");
        d.writeShort(port);
        writeVarInt(d, nextState);
        packet(out, body);
    }

    private static @NonNull ByteArrayOutputStream body(int id) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(new DataOutputStream(body), id);
        return body;
    }

    private static void packet(OutputStream out, @NonNull ByteArrayOutputStream body) throws IOException {
        byte[] bytes = body.toByteArray();
        DataOutputStream d = new DataOutputStream(out);
        writeVarInt(d, bytes.length);
        d.write(bytes);
        d.flush();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new IOException("connection closed");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    private static void writeString(DataOutputStream out, @NonNull String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[readVarInt(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

}
