package com.evops.report.service;

import lombok.Getter;
import lombok.Setter;

/**
 * 一份已渲染好的报告文件（单店 docx，或 zip 内的一个条目）。
 */
@Getter
@Setter
public class RenderedFile {
    /** 下载文件名（含扩展名，可能为中文） */
    private String fileName;
    /** 文件内容 */
    private byte[] content;

    public RenderedFile() {
    }

    public RenderedFile(String fileName, byte[] content) {
        this.fileName = fileName;
        this.content = content;
    }
}
