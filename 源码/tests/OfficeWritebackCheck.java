import com.ea.browser.tools.OfficeExtract;
import com.ea.browser.tools.OfficeWriteback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Office 写回回归：真实 docx/xlsx 样例 → 改文本 → 保存 → 再次解析。
 * 运行前用 jbr 的 javac 编译三个源码，见 tests/run_office_writeback.ps1。
 */
public final class OfficeWritebackCheck {

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "fixtures/office");
        int failures = 0;

        File docx = new File(root, "sample-real.docx");
        File docxTmp = new File(root, "writeback.docx");
        copy(docx, docxTmp);
        try {
            String before = OfficeExtract.docxText(docxTmp);
            if (!before.contains("例会")) throw new IOException("原始 docx 未包含预期段落");
            String edited = rstrip(before) + "\n写回校验行 W-2026\n";
            OfficeWriteback.saveDocx(docxTmp, edited);
            String after = OfficeExtract.docxText(docxTmp);
            if (!after.contains("写回校验行 W-2026")) throw new IOException("docx 写回后未解析到新增段落");
            if (!after.contains("例会")) throw new IOException("docx 写回后原段落丢失");
            System.out.println("[ok] docx 写回并回读 -> " + after.replace("\n", " | ").trim());
        } catch (Throwable t) {
            failures++;
            System.out.println("[FAIL] docx 写回: " + t.getMessage());
        } finally {
            docxTmp.delete();
        }

        File xlsx = new File(root, "sample-real.xlsx");
        File xlsxTmp = new File(root, "writeback.xlsx");
        copy(xlsx, xlsxTmp);
        try {
            String before = OfficeExtract.xlsxText(xlsxTmp);
            if (!before.contains("合计")) throw new IOException("原始 xlsx 未包含预期单元格");
            String[] rows = rstrip(before).split("\n", -1);
            StringBuilder edited = new StringBuilder();
            for (int i = 0; i < rows.length; i++) {
                if (i > 0) edited.append('\n');
                String[] cells = rows[i].split("\t", -1);
                if (i == 1) {
                    // 第二行第二列：既验证文本单元格，也验证数字单元格
                    cells[1] = "改名后的单元格";
                }
                edited.append(String.join("\t", cells));
            }
            OfficeWriteback.saveXlsx(xlsxTmp, edited.toString());
            String after = OfficeExtract.xlsxText(xlsxTmp);
            if (!after.contains("改名后的单元格")) throw new IOException("xlsx 写回后未解析到修改的单元格");
            if (!after.contains("合计")) throw new IOException("xlsx 写回后原有单元格丢失");
            System.out.println("[ok] xlsx 写回并回读 -> " + after.replace("\n", " | ").trim());
        } catch (Throwable t) {
            failures++;
            System.out.println("[FAIL] xlsx 写回: " + t.getMessage());
        } finally {
            xlsxTmp.delete();
        }

        // 空内容必须明确报错，不生成坏文件
        File emptyTmp = new File(root, "empty.docx");
        copy(docx, emptyTmp);
        try {
            OfficeWriteback.saveDocx(emptyTmp, "   \n");
            failures++;
            System.out.println("[FAIL] 空 docx 未报错");
        } catch (IOException expected) {
            System.out.println("[ok] 空 docx 拒绝保存: " + expected.getMessage());
        } finally {
            emptyTmp.delete();
        }

        System.out.println("RESULT failures=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }
}
