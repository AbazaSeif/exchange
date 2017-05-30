/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.gui.main.overlays.windows;

import com.google.common.base.Splitter;
import io.bisq.common.UserThread;
import io.bisq.common.locale.Res;
import io.bisq.common.util.Tuple2;
import io.bisq.core.btc.wallet.WalletsManager;
import io.bisq.core.crypto.ScryptUtil;
import io.bisq.gui.components.BusyAnimation;
import io.bisq.gui.components.PasswordTextField;
import io.bisq.gui.main.overlays.Overlay;
import io.bisq.gui.main.overlays.popups.Popup;
import io.bisq.gui.util.Transitions;
import io.bisq.gui.util.validation.PasswordValidator;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static io.bisq.gui.util.FormBuilder.*;
import static javafx.beans.binding.Bindings.createBooleanBinding;

public class WalletPasswordWindow extends Overlay<WalletPasswordWindow> {
    private static final Logger log = LoggerFactory.getLogger(WalletPasswordWindow.class);
    private Button unlockButton;
    private AesKeyHandler aesKeyHandler;
    private PasswordTextField passwordTextField;
    private Button forgotPasswordButton;
    private Button restoreButton;
    private TextArea seedWordsTextArea;
    private DatePicker restoreDatePicker;
    private final SimpleBooleanProperty seedWordsValid = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty dateValid = new SimpleBooleanProperty(false);
    private final BooleanProperty seedWordsEdited = new SimpleBooleanProperty();
    private ChangeListener<String> changeListener;
    private ChangeListener<String> wordsTextAreaChangeListener;
    private ChangeListener<Boolean> datePickerChangeListener;
    private ChangeListener<Boolean> seedWordsValidChangeListener;
    private ChangeListener<LocalDate> dateChangeListener;
    private LocalDate walletCreationDate;
    private final WalletsManager walletsManager;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Interface
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface AesKeyHandler {
        void onAesKey(KeyParameter aesKey);
    }

    @Inject
    private WalletPasswordWindow(WalletsManager walletsManager) {
        this.walletsManager = walletsManager;
        type = Type.Attention;
        width = 800;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void show() {
        if (gridPane != null) {
            rowIndex = -1;
            gridPane.getChildren().clear();
        }

        if (headLine == null)
            headLine = Res.get("walletPasswordWindow.headline");

        createGridPane();
        addHeadLine();
        addSeparator();
        addInputFields();
        addButtons();
        applyStyles();
        display();
    }

    public WalletPasswordWindow onAesKey(AesKeyHandler aesKeyHandler) {
        this.aesKeyHandler = aesKeyHandler;
        return this;
    }

    @Override
    protected void cleanup() {
        if (passwordTextField != null)
            passwordTextField.textProperty().removeListener(changeListener);

        if (seedWordsValidChangeListener != null) {
            seedWordsValid.removeListener(seedWordsValidChangeListener);
            dateValid.removeListener(datePickerChangeListener);
            seedWordsTextArea.textProperty().removeListener(wordsTextAreaChangeListener);
            restoreDatePicker.valueProperty().removeListener(dateChangeListener);
            restoreButton.disableProperty().unbind();
            restoreButton.setOnAction(null);
            seedWordsTextArea.setText("");
            restoreDatePicker.setValue(null);
            seedWordsTextArea.getStyleClass().remove("validation_error");
            restoreDatePicker.getStyleClass().remove("validation_error");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    private void addInputFields() {
        Label label = new Label(Res.get("password.enterPassword"));
        label.setWrapText(true);
        GridPane.setMargin(label, new Insets(3, 0, 0, 0));
        GridPane.setRowIndex(label, ++rowIndex);

        passwordTextField = new PasswordTextField();
        GridPane.setMargin(passwordTextField, new Insets(3, 0, 0, 0));
        GridPane.setRowIndex(passwordTextField, rowIndex);
        GridPane.setColumnIndex(passwordTextField, 1);
        PasswordValidator passwordValidator = new PasswordValidator();
        changeListener = (observable, oldValue, newValue) -> unlockButton.setDisable(!passwordValidator.validate(newValue).isValid);
        passwordTextField.textProperty().addListener(changeListener);
        gridPane.getChildren().addAll(label, passwordTextField);
    }

    private void addButtons() {
        BusyAnimation busyAnimation = new BusyAnimation(false);
        Label deriveStatusLabel = new Label();

        unlockButton = new Button(Res.get("shared.unlock"));
        unlockButton.setDefaultButton(true);
        unlockButton.setDisable(true);
        unlockButton.setOnAction(e -> {
            String password = passwordTextField.getText();
            checkArgument(password.length() < 50, Res.get("password.tooLong"));
            KeyCrypterScrypt keyCrypterScrypt = walletsManager.getKeyCrypterScrypt();
            if (keyCrypterScrypt != null) {
                busyAnimation.play();
                deriveStatusLabel.setText(Res.get("password.deriveKey"));
                ScryptUtil.deriveKeyWithScrypt(keyCrypterScrypt, password, aesKey -> {
                    if (walletsManager.checkAESKey(aesKey)) {
                        if (aesKeyHandler != null)
                            aesKeyHandler.onAesKey(aesKey);

                        hide();
                    } else {
                        busyAnimation.stop();
                        deriveStatusLabel.setText("");

                        UserThread.runAfter(() -> new Popup<>()
                                .warning(Res.get("password.wrongPw"))
                                .onClose(this::blurAgain).show(), Transitions.DEFAULT_DURATION, TimeUnit.MILLISECONDS);
                    }
                });
            } else {
                log.error("wallet.getKeyCrypter() is null, that must not happen.");
            }
        });

        forgotPasswordButton = new Button(Res.get("password.forgotPassword"));
        forgotPasswordButton.setOnAction(e -> {
            forgotPasswordButton.setDisable(true);
            unlockButton.setDefaultButton(false);
            showRestoreScreen();
        });

        Button cancelButton = new Button(Res.get("shared.cancel"));
        cancelButton.setOnAction(event -> {
            hide();
            closeHandlerOptional.ifPresent(Runnable::run);
        });

        HBox hBox = new HBox();
        hBox.setMinWidth(560);
        hBox.setSpacing(10);
        GridPane.setRowIndex(hBox, ++rowIndex);
        GridPane.setColumnIndex(hBox, 1);
        hBox.setAlignment(Pos.CENTER_LEFT);
        if (hideCloseButton)
            hBox.getChildren().addAll(unlockButton, forgotPasswordButton, busyAnimation, deriveStatusLabel);
        else
            hBox.getChildren().addAll(unlockButton, cancelButton);
        gridPane.getChildren().add(hBox);


        ColumnConstraints columnConstraints1 = new ColumnConstraints();
        columnConstraints1.setHalignment(HPos.RIGHT);
        columnConstraints1.setHgrow(Priority.SOMETIMES);
        ColumnConstraints columnConstraints2 = new ColumnConstraints();
        columnConstraints2.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(columnConstraints1, columnConstraints2);
    }

    private void showRestoreScreen() {
        Label headLine2Label = new Label(Res.get(Res.get("seed.restore.title")));
        headLine2Label.setId("popup-headline");
        headLine2Label.setMouseTransparent(true);
        GridPane.setHalignment(headLine2Label, HPos.LEFT);
        GridPane.setRowIndex(headLine2Label, ++rowIndex);
        GridPane.setColumnSpan(headLine2Label, 2);
        GridPane.setMargin(headLine2Label, new Insets(30, 0, 0, 0));
        gridPane.getChildren().add(headLine2Label);

        Separator separator = new Separator();
        separator.setMouseTransparent(true);
        separator.setOrientation(Orientation.HORIZONTAL);
        separator.setStyle("-fx-background: #ccc;");
        GridPane.setHalignment(separator, HPos.CENTER);
        GridPane.setRowIndex(separator, ++rowIndex);
        GridPane.setColumnSpan(separator, 2);

        gridPane.getChildren().add(separator);

        Tuple2<Label, TextArea> tuple = addLabelTextArea(gridPane, ++rowIndex, Res.get("seed.seedWords"), "", 5);
        seedWordsTextArea = tuple.second;
        seedWordsTextArea.setPrefHeight(60);
        seedWordsTextArea.setStyle("-fx-border-color: #ddd;");

        Tuple2<Label, DatePicker> labelDatePickerTuple2 = addLabelDatePicker(gridPane, ++rowIndex,
                Res.get("seed.creationDate"));
        restoreDatePicker = labelDatePickerTuple2.second;
        restoreButton = addButton(gridPane, ++rowIndex, Res.get("seed.restore"));
        restoreButton.setDefaultButton(true);
        stage.setHeight(340);


        // wallet creation date is not encrypted
        walletCreationDate = Instant.ofEpochSecond(walletsManager.getChainSeedCreationTimeSeconds()).atZone(ZoneId.systemDefault()).toLocalDate();

        restoreButton.disableProperty().bind(createBooleanBinding(() -> !seedWordsValid.get() || !dateValid.get() || !seedWordsEdited.get(),
                seedWordsValid, dateValid, seedWordsEdited));


        seedWordsValidChangeListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                seedWordsTextArea.getStyleClass().remove("validation_error");
            } else {
                seedWordsTextArea.getStyleClass().add("validation_error");
            }
        };

        wordsTextAreaChangeListener = (observable, oldValue, newValue) -> {
            seedWordsEdited.set(true);
            try {
                MnemonicCode codec = new MnemonicCode();
                codec.check(Splitter.on(" ").splitToList(newValue));
                seedWordsValid.set(true);
            } catch (IOException | MnemonicException e) {
                seedWordsValid.set(false);
            }
        };

        datePickerChangeListener = (observable, oldValue, newValue) -> {
            if (newValue)
                restoreDatePicker.getStyleClass().remove("validation_error");
            else
                restoreDatePicker.getStyleClass().add("validation_error");
        };
        dateChangeListener = (observable, oldValue, newValue) -> dateValid.set(walletCreationDate.equals(newValue));

        seedWordsValid.addListener(seedWordsValidChangeListener);
        dateValid.addListener(datePickerChangeListener);
        seedWordsTextArea.textProperty().addListener(wordsTextAreaChangeListener);
        restoreDatePicker.valueProperty().addListener(dateChangeListener);
        restoreButton.disableProperty().bind(createBooleanBinding(() -> !seedWordsValid.get() || !dateValid.get() || !seedWordsEdited.get(),
                seedWordsValid, dateValid, seedWordsEdited));

        restoreButton.setOnAction(e -> onRestore());

        seedWordsTextArea.getStyleClass().remove("validation_error");
        restoreDatePicker.getStyleClass().remove("validation_error");

        layout();
    }

    private void onRestore() {
        if (walletsManager.hasPositiveBalance()) {
            new Popup<>().warning(Res.get("seed.warn.walletNotEmpty.msg"))
                    .actionButtonText(Res.get("seed.warn.walletNotEmpty.restore"))
                    .onAction(this::checkIfEncrypted)
                    .closeButtonText(Res.get("seed.warn.walletNotEmpty.emptyWallet"))
                    .show();
        } else {
            checkIfEncrypted();
        }
    }

    private void checkIfEncrypted() {
        if (walletsManager.areWalletsEncrypted()) {
            new Popup<>().information(Res.get("seed.warn.notEncryptedAnymore"))
                    .closeButtonText(Res.get("shared.no"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(this::doRestore)
                    .show();
        } else {
            doRestore();
        }
    }

    private void doRestore() {
        long date = restoreDatePicker.getValue().atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        DeterministicSeed seed = new DeterministicSeed(Splitter.on(" ").splitToList(seedWordsTextArea.getText()), null, "", date);
        walletsManager.restoreSeedWords(
                seed,
                () -> UserThread.execute(() -> {
                    log.debug("Wallet restored with seed words");
                    new Popup<>().feedback(Res.get("seed.restore.success"))
                            .useShutDownButton();
                }),
                throwable -> UserThread.execute(() -> {
                    log.error(throwable.getMessage());
                    new Popup<>().error(Res.get("seed.restore.error", Res.get("shared.errorMessageInline",
                            throwable.getMessage())))
                            .show();
                }));
    }
}