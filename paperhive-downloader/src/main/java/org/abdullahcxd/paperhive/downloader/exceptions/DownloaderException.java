package org.abdullahcxd.paperhive.downloader.exceptions;

public class DownloaderException extends RuntimeException {
    public DownloaderException(String message) {
        super(message);
    }
    public DownloaderException(String message, Exception e) {
        super(message, e);
    }
}
