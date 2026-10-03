package com.rescueauth.v2.ui.authenticator

import com.rescueauth.v2.scanner.ScannerResultRouter

/** Only the unscoped home accepts migration QR; the verified import adapter retains its state and parser. */
internal fun handleAccountAddScan(raw: String, editor: AccountAddViewModel, authenticator: AuthenticatorViewModel,
    closeCamera: () -> Unit) {
    if (editor.formState.value.scope == AccountAddScope.VAULT) {
        when (val result = ScannerResultRouter.route(raw)) {
            is ScannerResultRouter.ScanResult.Totp -> { closeCamera(); editor.onParsedTotp(result.parsed) }
            is ScannerResultRouter.ScanResult.Migration -> {
                closeCamera(); editor.dismiss(); authenticator.openScanner(); authenticator.onQrScanned(raw)
            }
            else -> authenticator.onQrScanned(raw)
        }
    } else { closeCamera(); editor.onScanned(raw) }
}
