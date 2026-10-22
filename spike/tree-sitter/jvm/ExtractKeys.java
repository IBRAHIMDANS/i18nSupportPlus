import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterTsx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spike: the same TSX key extraction as ../node/extract.mjs, on the JVM through the JNI binding
 * (io.github.bonede:tree-sitter + tree-sitter-tsx), to check it loads and parses under Java 21.
 * Only the call sites are listed: the namespace inference lives in the Node prototype.
 *
 * Run (Java 21, no build): java -cp 'jars/*' ExtractKeys.java <file.tsx>...
 */
public class ExtractKeys {

    // `t('key')`, `i18n.t('key')`, `t(`a.${b}`)`, and `<Trans i18nKey="key">`. The names are
    // filtered in Java: predicates (#eq?, #match?) are not applied by the cursor of this binding.
    private static final String QUERY = """
        (call_expression
          function: [(identifier) (member_expression)] @fn
          arguments: (arguments . [(string) (template_string)] @key))
        (jsx_attribute
          (property_identifier) @fn
          (string) @key)
        """;
    private static final java.util.Set<String> NAMES = java.util.Set.of("t", "i18n.t", "i18nKey");

    public static void main(String[] args) throws Exception {
        TSParser parser = new TSParser();
        TreeSitterTsx tsx = new TreeSitterTsx();
        parser.setLanguage(tsx);
        TSQuery query = new TSQuery(tsx, QUERY);

        for (String file : args) {
            byte[] bytes = Files.readAllBytes(Path.of(file));
            String source = new String(bytes, StandardCharsets.UTF_8);
            long started = System.nanoTime();
            TSTree tree = parser.parseString(null, source);
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(query, tree.getRootNode());
            TSQueryMatch match = new TSQueryMatch();
            int count = 0;
            StringBuilder out = new StringBuilder();
            while (cursor.nextMatch(match)) {
                TSNode name = null;
                TSNode node = null;
                for (TSQueryCapture capture : match.getCaptures()) {
                    if (query.getCaptureNameForId(capture.getIndex()).equals("fn")) name = capture.getNode();
                    else node = capture.getNode();
                }
                if (name == null || node == null) continue;
                if (!NAMES.contains(new String(bytes, name.getStartByte(), name.getEndByte() - name.getStartByte(), StandardCharsets.UTF_8))) continue;
                {
                    // Byte offsets: the sample files are ASCII, so they are char offsets too.
                    String text = new String(bytes, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
                    out.append(String.format("  %3d %s%n", node.getStartPoint().getRow() + 1, text));
                    count++;
                }
            }
            long micros = (System.nanoTime() - started) / 1_000;
            System.out.printf("== %s errors=%s keys=%d micros=%d%n%s",
                Path.of(file).getFileName(), tree.getRootNode().hasError(), count, micros, out);
        }
    }
}
