package dev.onionp2p.lokinetcall;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;

public final class LokinetCallApp extends Application {
    private final Label status = new Label("Starting UDP listener…");
    private final TextField listenPort = new TextField("9922");
    private final TextField peerAddress = new TextField();
    private final TextField peerPort = new TextField("9922");
    private final PasswordField callKey = new PasswordField();
    private final Button callButton = new Button("Call");
    private final Button acceptButton = new Button("Accept");
    private final Button hangupButton = new Button("Hang up");
    private CallSession session;

    @Override
    public void start(Stage stage) {
        listenPort.setPrefColumnCount(6);
        peerPort.setPrefColumnCount(6);
        peerAddress.setPromptText("Friend’s Lokinet address (.loki)");
        callKey.setPromptText("Shared call key (same on both devices)");
        callButton.setDefaultButton(true);
        acceptButton.setDisable(true);
        hangupButton.setDisable(true);

        HBox localRow = new HBox(10, new Label("Listen on UDP"), listenPort, new Label("(keep this port reachable over Lokinet)"));
        localRow.setAlignment(Pos.CENTER_LEFT);
        HBox peerRow = new HBox(8, peerAddress, peerPort);
        peerRow.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, callButton, acceptButton, hangupButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(14,
                new Label("Lokinet Call") {{ getStyleClass().add("title"); }},
                new Label("A tiny encrypted audio-call experiment over Lokinet."),
                new Label("Your UDP port"), localRow,
                new Label("Call a peer"), peerRow,
                callKey,
                actions,
                status);
        root.setPadding(new Insets(22));
        root.getStyleClass().add("root");

        callButton.setOnAction(event -> startCall());
        acceptButton.setOnAction(event -> acceptCall());
        hangupButton.setOnAction(event -> hangUp());
        callKey.textProperty().addListener((observable, oldValue, newValue) -> {
            if (session != null) session.setSharedKey(newValue);
        });

        stage.setTitle("Lokinet Call");
        stage.setMinWidth(440);
        stage.setMinHeight(360);
        stage.setScene(new Scene(root, 500, 400));
        stage.getScene().getStylesheets().add(getClass().getResource("/call.css").toExternalForm());
        stage.setOnCloseRequest(event -> closeSession());
        stage.show();
        bindListener();
    }

    private void bindListener() {
        try {
            int port = Integer.parseInt(listenPort.getText().strip());
            session = new CallSession(port, this::incomingCall, this::callStateChanged, this::reportError);
            session.setSharedKey(callKey.getText());
            status.setText("Listening on UDP " + port + ". Make sure Lokinet is running.");
        } catch (Exception e) {
            status.setText("Could not listen: " + e.getMessage());
            reportError(e.getMessage());
        }
    }

    private void startCall() {
        if (session == null) { bindListener(); if (session == null) return; }
        try {
            session.setSharedKey(callKey.getText());
            session.call(peerAddress.getText().strip(), Integer.parseInt(peerPort.getText().strip()));
            status.setText("Calling " + peerAddress.getText().strip() + "…");
            callButton.setDisable(true);
        } catch (Exception e) { reportError(e.getMessage()); }
    }

    private void incomingCall(String remote) {
        Platform.runLater(() -> {
            peerAddress.setText(remote.substring(0, remote.lastIndexOf(':')));
            peerPort.setText(remote.substring(remote.lastIndexOf(':') + 1));
            status.setText("Incoming call from " + remote);
            acceptButton.setDisable(false);
        });
    }

    private void acceptCall() {
        try {
            session.setSharedKey(callKey.getText());
            session.accept();
            acceptButton.setDisable(true);
            hangupButton.setDisable(false);
            callButton.setDisable(true);
            status.setText("Connecting audio…");
        } catch (Exception e) { reportError(e.getMessage()); }
    }

    private void hangUp() {
        if (session != null) session.hangUp();
        callButton.setDisable(false);
        acceptButton.setDisable(true);
        hangupButton.setDisable(true);
        status.setText("Call ended. Still listening on UDP " + listenPort.getText() + ".");
    }

    private void callStateChanged(String message, boolean active) {
        Platform.runLater(() -> {
            status.setText(message);
            callButton.setDisable(active);
            hangupButton.setDisable(!active);
            if (!active) acceptButton.setDisable(true);
        });
    }

    private void reportError(String message) {
        Platform.runLater(() -> status.setText(message));
    }

    private void closeSession() {
        if (session != null) session.close();
    }

    @Override
    public void stop() { closeSession(); }

    public static void main(String[] args) { launch(args); }
}
