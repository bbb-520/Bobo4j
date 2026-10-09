package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.parsing.IsolatedTikaParser;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class TikaParserTest {
    @TempDir Path dir;
    @Test void realPdfTextRetainsActualSecondPage()throws Exception{
        Path input=dir.resolve("report.pdf");
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){
            for(String text:java.util.List.of("first page","TAIL_VALUE_97")){
                var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){
                    content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText(text);content.endText();
                }
            }
            pdf.save(input.toFile());
        }
        var parsed=new IsolatedTikaParser().parse(input,dir,200000,Duration.ofSeconds(30));
        assertThat(parsed.mediaType()).contains("pdf");
        assertThat(parsed.blocks()).anySatisfy(b->{assertThat(b.text()).contains("TAIL_VALUE_97");assertThat(b.page()).isEqualTo(2);});
    }
    @Test void realWordAndSpreadsheetRetainTailNumbersWithoutInventedPages()throws Exception{
        Path word=dir.resolve("report.docx"),sheet=dir.resolve("report.xlsx");
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var output=Files.newOutputStream(word)){
            document.createParagraph().createRun().setText("Word尾部预算97万元");document.write(output);
        }
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=Files.newOutputStream(sheet)){
            workbook.createSheet("预算").createRow(0).createCell(0).setCellValue("Excel尾部预算97万元");workbook.write(output);
        }
        for(Path input:java.util.List.of(word,sheet)){
            var parsed=new IsolatedTikaParser().parse(input,dir,200000,Duration.ofSeconds(30));
            assertThat(parsed.mediaType()).contains("officedocument");
            assertThat(parsed.blocks()).anySatisfy(b->{assertThat(b.text()).contains("97万元");assertThat(b.page()).isNull();});
        }
    }
    @Test void htmlTableHeadingAndTailHaveRealSourcePositions()throws Exception{
        Path input=dir.resolve("misleading.pdf");Files.writeString(input,"<html><body><h1>Budget</h1><table><tr><th>Item</th><th>Value</th></tr><tr><td>尾部</td><td>97</td></tr></table><p>Final sentence.</p></body></html>");
        var parsed=new IsolatedTikaParser().parse(input,dir,200000,Duration.ofSeconds(30));
        assertThat(parsed.mediaType()).contains("html");assertThat(parsed.blocks()).anySatisfy(b->{assertThat(b.text()).contains("97");assertThat(b.title()).contains("Budget");assertThat(b.page()).isNull();assertThat(b.charEnd()).isGreaterThan(b.charStart());});
        assertThat(parsed.blocks().getLast().text()).contains("Final sentence");
    }
    @Test void blankTextIsNotAReadyScannedDocument()throws Exception{
        Path input=dir.resolve("empty.txt");Files.writeString(input,"  \n ");
        assertThatThrownBy(()->new IsolatedTikaParser().parse(input,dir,100,Duration.ofSeconds(30))).hasMessage("NO_TEXT_LAYER");
    }
    @Test void outputLimitNeverReturnsPartialSuccess()throws Exception{
        Path input=dir.resolve("large.txt");Files.writeString(input,"中文".repeat(2000));
        assertThatThrownBy(()->new IsolatedTikaParser().parse(input,dir,100,Duration.ofSeconds(30))).hasMessage("PARSE_OUTPUT_LIMIT");
    }
    @Test void brokenPdfReportsStableFailure()throws Exception{
        Path input=dir.resolve("broken.pdf");Files.writeString(input,"%PDF-1.7\n broken object");
        assertThatThrownBy(()->new IsolatedTikaParser().parse(input,dir,1000,Duration.ofSeconds(30))).hasMessage("DOCUMENT_CORRUPT_OR_ENCRYPTED");
    }
}
