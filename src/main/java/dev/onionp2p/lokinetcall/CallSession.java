package dev.onionp2p.lokinetcall;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Small UDP voice-call experiment. Not a production media protocol. */
final class CallSession implements AutoCloseable {
    private static final byte[] AUDIO_MAGIC = {'O', 'A', 'U', '1'};
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_BYTES = 640; // 20 ms of mono 16-bit PCM

    private final DatagramSocket socket;
    private final Consumer<String> incoming;
    private final BiConsumer<String, Boolean> state;
    private final Consumer<String> errors;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] sharedKey;
    private volatile InetSocketAddress peer;
    private volatile InetSocketAddress pendingPeer;
    private volatile String callId;
    private volatile String pendingCallId;
    private volatile boolean active;
    private volatile boolean running = true;
    private volatile boolean mediaRunning;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speaker;
    private long lastInviteAt;
    private long inviteStartedAt;

    CallSession(int port, Consumer<String> incoming, BiConsumer<String, Boolean> state, Consumer<String> errors)
            throws SocketException {
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(500);
        this.incoming = incoming;
        this.state = state;
        this.errors = errors;
        Thread.ofPlatform().daemon().name("lokinet-call-udp").start(this::receiveLoop);
    }

    void setSharedKey(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            sharedKey = null;
            return;
        }
        try {
            sharedKey = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void call(String host, int port) throws Exception {
        ensureKey();
        if (active) throw new IllegalStateException("A call is already active.");
        if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Enter a peer address and valid UDP port.");
        peer = new InetSocketAddress(InetAddress.getByName(host), port);
        callId = randomId();
        inviteStartedAt = lastInviteAt = System.currentTimeMillis();
        sendControl("INVITE", callId, peer);
        state.accept("Calling " + host + "…", false);
    }

    synchronized void accept() throws Exception {
        ensureKey();
        if (pendingPeer == null || pendingCallId == null) throw new IllegalStateException("There is no incoming call to accept.");
        peer = pendingPeer;
        callId = pendingCallId;
        pendingPeer = null;
        pendingCallId = null;
        sendControl("ACCEPT", callId, peer);
        startMedia();
    }

    synchronized void hangUp() {
        if (peer != null && callId != null) {
            try { sendControl("BYE", callId, peer); } catch (Exception ignored) { }
        }
        endMedia("Call ended.");
        peer = null;
        callId = null;
        pendingPeer = null;
        pendingCallId = null;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                if (startsWith(data, AUDIO_MAGIC)) {
                    if (active && samePeer(from, peer)) playAudio(data);
                } else {
                    handleControl(new String(data, StandardCharsets.UTF_8), from);
                }
            } catch (java.net.SocketTimeoutException ignored) {
                checkInviteTimeout();
            } catch (SocketException stopped) {
                if (running) errors.accept("Lokinet UDP listener stopped: " + stopped.getMessage());
                break;
            } catch (Exception e) {
                if (running) errors.accept("Call error: " + e.getMessage());
            }
        }
    }

    private void handleControl(String message, InetSocketAddress from) throws Exception {
        String[] fields = message.split(" ");
        if (fields.length != 3 || !verify(fields[0], fields[1], fields[2])) return;
        String type = fields[0];
        String id = fields[1];
        if ("INVITE".equals(type) && !active) {
            pendingPeer = from;
            pendingCallId = id;
            incoming.accept(from.getAddress().getHostAddress() + ":" + from.getPort());
        } else if ("ACCEPT".equals(type) && id.equals(callId) && samePeer(from, peer)) {
            startMedia();
        } else if ("BYE".equals(type) && id.equals(callId) && samePeer(from, peer)) {
            hangUpLocally();
        }
    }

    private synchronized void startMedia() throws Exception {
        if (active) return;
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
        microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
        speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        microphone.open(format, FRAME_BYTES * 6);
        speaker.open(format, FRAME_BYTES * 6);
        microphone.start();
        speaker.start();
        active = true;
        mediaRunning = true;
        Thread.ofPlatform().daemon().name("lokinet-call-microphone").start(this::captureLoop);
        state.accept("Call connected · 16 kHz encrypted audio", true);
    }

    private void captureLoop() {
        byte[] frame = new byte[FRAME_BYTES];
        while (running && mediaRunning) {
            TargetDataLine mic = microphone;
            InetSocketAddress destination = peer;
            if (mic == null || destination == null) break;
            int count = mic.read(frame, 0, frame.length);
            if (count <= 0) continue;
            try {
                byte[] encrypted = encrypt(Arrays.copyOf(frame, count));
                byte[] packet = ByteBuffer.allocate(AUDIO_MAGIC.length + encrypted.length).put(AUDIO_MAGIC).put(encrypted).array();
                socket.send(new DatagramPacket(packet, packet.length, destination));
            } catch (Exception e) {
                if (mediaRunning) errors.accept("Audio send failed: " + e.getMessage());
                break;
            }
        }
    }

    private void playAudio(byte[] packet) {
        try {
            byte[] encrypted = Arrays.copyOfRange(packet, AUDIO_MAGIC.length, packet.length);
            byte[] pcm = decrypt(encrypted);
            SourceDataLine output = speaker;
            if (output != null) output.write(pcm, 0, pcm.length);
        } catch (Exception ignored) {
            // A malformed or unauthenticated audio datagram is dropped.
        }
    }

    private byte[] encrypt(byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(audioKey(), "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plain);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    private byte[] decrypt(byte[] input) throws Exception {
        if (input.length < 28) throw new IOException("Short audio packet");
        byte[] nonce = Arrays.copyOfRange(input, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(audioKey(), "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(input, 12, input.length - 12);
    }

    private byte[] audioKey() throws Exception {
        ensureKey();
        return MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(sharedKey.length + 5)
                .put(sharedKey).put("audio".getBytes(StandardCharsets.US_ASCII)).array());
    }

    private void sendControl(String type, String id, InetSocketAddress destination) throws Exception {
        String message = type + " " + id + " " + mac(type, id);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, destination));
    }

    private boolean verify(String type, String id, String supplied) throws Exception {
        ensureKey();
        return MessageDigest.isEqual(mac(type, id).getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private String mac(String type, String id) throws Exception {
        ensureKey();
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] signalKey = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(sharedKey.length + 7)
                .put(sharedKey).put("signal".getBytes(StandardCharsets.US_ASCII)).array());
        mac.init(new SecretKeySpec(signalKey, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((type + ":" + id).getBytes(StandardCharsets.UTF_8)));
    }

    private void checkInviteTimeout() {
        if (callId == null || active) return;
        long now = System.currentTimeMillis();
        if (now - inviteStartedAt > 30_000) {
            peer = null;
            callId = null;
            state.accept("No answer. Check that both Lokinet clients are online and the peer address is correct.", false);
        } else if (now - lastInviteAt >= 2_000) {
            try { sendControl("INVITE", callId, peer); lastInviteAt = now; }
            catch (Exception e) { errors.accept("Could not send call invite: " + e.getMessage()); }
        }
    }

    private synchronized void hangUpLocally() {
        endMedia("The other person ended the call.");
        peer = null;
        callId = null;
    }

    private synchronized void endMedia(String message) {
        mediaRunning = false;
        active = false;
        closeLine(microphone);
        closeLine(speaker);
        microphone = null;
        speaker = null;
        state.accept(message, false);
    }

    private static void closeLine(javax.sound.sampled.DataLine line) {
        if (line != null) { line.stop(); line.close(); }
    }

    private static boolean startsWith(byte[] input, byte[] prefix) {
        if (input.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (input[i] != prefix[i]) return false;
        return true;
    }

    private static boolean samePeer(InetSocketAddress left, InetSocketAddress right) {
        return left != null && right != null && left.getAddress().equals(right.getAddress()) && left.getPort() == right.getPort();
    }

    private String randomId() {
        byte[] id = new byte[12];
        random.nextBytes(id);
        return HexFormat.of().formatHex(id);
    }

    private void ensureKey() {
        if (sharedKey == null || sharedKey.length == 0) throw new IllegalStateException("Enter the same private call key on both devices first.");
    }

    @Override
    public void close() {
        running = false;
        hangUp();
        socket.close();
    }
}
