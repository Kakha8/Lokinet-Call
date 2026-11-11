# Lokinet Call

A minimal JavaFX proof of concept for a one-to-one audio call over the operating system's Lokinet interface.

## Requirements

- Java 21
- Maven (or use the included Maven wrapper)
- Lokinet installed and running on both devices
- Both devices must be able to resolve/reach each other's Lokinet `.loki` endpoint over UDP
- Microphone and speakers/headphones

This app does not bundle or install Lokinet. Lokinet runs separately and provides the overlay network; the app sends UDP audio through the OS network interface. Make sure the peer endpoint is configured to accept UDP traffic on the chosen port (default `9922`), and allow the Java app through the local firewall if prompted.

## Run

```powershell
./mvnw.cmd javafx:run
```

Or with Maven installed:

```powershell
mvn javafx:run
```

## Try a call

1. Start Lokinet on both PCs and wait until each client is connected.
2. Start this app on both PCs. Each app listens on UDP `9922` by default.
3. Enter the same private call key on both devices. Exchange it privately; it is used to authenticate call setup and encrypt audio.
4. On the caller, enter the other device's reachable `.loki` endpoint and listening port, then click **Call**.
5. On the other device, click **Accept** when the incoming call appears.

The prototype uses 16 kHz mono PCM with AES-GCM encryption. It has no codec, echo cancellation, jitter buffer, adaptive bitrate, or robust recovery. Expect higher bandwidth and rougher audio than a production calling app. This is a connectivity experiment, not a production-ready calling or security implementation.

## Notes

- Call signaling and audio are sent as UDP datagrams through Lokinet.
- The shared call key must match on both devices. If the key is wrong, invite authentication or audio decryption fails.
- You may need to configure Lokinet endpoints and firewall rules for inbound UDP.
- Lokinet is a separate native application; this JavaFX project does not embed its runtime.
