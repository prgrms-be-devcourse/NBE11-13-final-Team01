package com.zipick.evaluation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PdfLayoutDumpApplication {
    private PdfLayoutDumpApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path pdf = Path.of(System.getenv().getOrDefault(
                "PDF_LAYOUT_FILE",
                ".parser-evaluation/documents/gh-795-purchase-rental-housing-list.pdf"
        ));
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(pdf))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            System.out.print(stripper.getText(document));
        }
    }
}
