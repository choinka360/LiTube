package com.hhst.youtubelite.extractor.exception;

/** Expected content availability, rather than an application failure. */
public final class VideoUnavailableException extends ExtractionException {
    public VideoUnavailableException(String message, Throwable cause) { super(message, cause); }
}
