package by.mxrpheus;

public class FileTransferInfo {
    private String filename;
    private Long bytesTransferred;

    FileTransferInfo(String filename, Long bytesTransferred) {
        this.filename = filename;
        this.bytesTransferred = bytesTransferred;
    }

    public String getFilename() {
        return filename;
    }

    public Long getBytesTransferred() {
        return bytesTransferred;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public void setBytesTransferred(Long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }
}
