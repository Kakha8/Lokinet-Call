# Lokinet Call

A minimal JavaFX proof of concept for a one-to-one audio call over the operating system's Lokinet interface.

## Requirements

- Java 21
- Maven (or use the included Maven wrapper)
- Lokinet installed and running on both devices
- Both devices must be able to resolve/reach each other's Lokinet `.loki` endpoint over UDP
- Microphone and speakers/headphones

This app does not bundle or install Lokinet. Lokinet runs separately and provides the overlay network; the app sends UDP audio through the OS network interface. On first launch, the app adds a persistent SNApp `keyfile=` entry to the Windows Lokinet config (typically `%APPDATA%\Lokinet\lokinet.ini`) and chooses a private key path beside it. If you have an existing `keyfile=` entry, it preserves and uses it. Restart Lokinet once after first-time setup so it loads the key and publishes the endpoint. On the next app launch, the app looks up `localhost.loki` through Lokinet DNS and displays the resulting `.loki:9922` address with a **Copy** button.

The endpoint key is stored locally as `onionp2p-call-endpoint.private` (unless your config already specified a keyfile). Keep this file private and back it up securely: losing it changes the endpoint address. The app does not restart the Lokinet service automatically.

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
2. Start this app on both PCs. On first launch, restart Lokinet when prompted, then reopen the app. Each app listens on UDP `9922` by default and displays its call address.
3. Enter the same private call key on both devices. Exchange it privately; it is used to authenticate call setup and encrypt audio.
4. On the caller, enter the other device's displayed `.loki` address and listening port, then click **Call**.
5. On the other device, click **Accept** when the incoming call appears.

The prototype uses 16 kHz mono PCM with AES-GCM encryption. It has no codec, echo cancellation, jitter buffer, adaptive bitrate, or robust recovery. Expect higher bandwidth and rougher audio than a production calling app. This is a connectivity experiment, not a production-ready calling or security implementation.

## Notes

- Call signaling and audio are sent as UDP datagrams through Lokinet.
- The shared call key must match on both devices. If the key is wrong, invite authentication or audio decryption fails.
- Allow the Java app through the local firewall if prompted. If the address stays in “Looking up” state, check that Lokinet is running, has loaded the configured keyfile, and that Lokinet DNS is available. The app only reports an address returned by the local Lokinet resolver.
- Lokinet is a separate native application; this JavaFX project does not embed its runtime.
