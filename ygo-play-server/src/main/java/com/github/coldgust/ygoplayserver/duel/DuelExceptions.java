package com.github.coldgust.ygoplayserver.duel;

/** 服务层异常，由 web 层映射为 400/404/409。 */
public final class DuelExceptions {

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private DuelExceptions() {
    }
}
