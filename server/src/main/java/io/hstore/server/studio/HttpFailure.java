package io.hstore.server.studio;

final class HttpFailure extends RuntimeException {

    private final int status;

    private HttpFailure(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }

    static HttpFailure badRequest(String message) {
        return new HttpFailure(400, message);
    }

    static HttpFailure unauthorized(String message) {
        return new HttpFailure(401, message);
    }

    static HttpFailure forbidden(String message) {
        return new HttpFailure(403, message);
    }

    static HttpFailure notFound(String message) {
        return new HttpFailure(404, message);
    }

    static HttpFailure tooLarge(String message) {
        return new HttpFailure(413, message);
    }
}
