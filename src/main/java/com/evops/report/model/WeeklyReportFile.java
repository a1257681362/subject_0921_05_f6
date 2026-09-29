package com.evops.report.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 一次周报产出结果：快照 + 文件名 + docx 字节。
 */
@Getter
@Setter
public class WeeklyReportFile {
    private WeeklyReportSnapshot snapshot;
    private String fileName;
    private byte[] content;

    public WeeklyReportFile(WeeklyReportSnapshot snapshot, String fileName, byte[] content) {
        this.snapshot = snapshot;
        this.fileName = fileName;
        this.content = content;
    }
}
