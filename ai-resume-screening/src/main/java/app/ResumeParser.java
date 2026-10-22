package app;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class ResumeParser {
    public static String extract(byte[] data, String ext) throws Exception {
        switch (ext) {
            case "pdf":
                try (PDDocument doc = PDDocument.load(data)) {
                    return new PDFTextStripper().getText(doc);
                }
            case "docx":
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
                     XWPFWordExtractor x = new XWPFWordExtractor(doc)) {
                    return x.getText();
                }
            default:
                return new String(data, StandardCharsets.UTF_8);
        }
    }
}
