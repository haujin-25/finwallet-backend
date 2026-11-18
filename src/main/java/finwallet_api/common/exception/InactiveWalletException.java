package finwallet_api.common.exception;

public class InactiveWalletException extends RuntimeException {

    public InactiveWalletException(String message) {
        super(message);
    }
}
