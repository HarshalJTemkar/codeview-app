package com.codeview.app.chunker;

import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ImportDeclaration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Structure-aware chunker for Java source: parses an AST via JavaParser and
 * emits one chunk per class/interface and one chunk per method, matching the
 * source doc's "self-contained, semantically coherent" chunk goal (functions,
 * classes) for the Java-first scope confirmed for this build.
 *
 * NOTE: the source design doc specifies Tree-sitter. JavaParser is used here
 * instead because Tree-sitter's Java grammar has no straightforward Maven
 * Central artifact to depend on — this is a stated deviation, not a silent
 * substitution. A fixed-line/brace fallback (§ "Language-Agnostic vs
 * Language-Aware" in the source doc) is not implemented in this build; only
 * Java is in scope per your confirmed decision.
 *
 * BUG FIX: earlier versions of this class used StaticJavaParser.parse(),
 * which defaults to an older language level and rejected records, pattern-
 * matching instanceof, and text blocks — exactly the Java 21 features this
 * project's own source uses, which is why running CodeView against itself
 * failed on nearly every file. Fixed by building a dedicated JavaParser
 * instance per call with LanguageLevel.BLEEDING_EDGE (the newest feature set
 * the JavaParser release supports), rather than relying on a default. This
 * also sidesteps StaticJavaParser's shared mutable configuration, which is
 * a real concern under the parallel worker pool (Architecture Plan §12) —
 * each chunk() call now gets its own JavaParser instance instead of
 * contending over global static state.
 */
@Component
public class JavaAstChunker {

    private final MerkleHasher hasher;
    private final ParserConfiguration parserConfig;

    public JavaAstChunker(MerkleHasher hasher) {
        this.hasher = hasher;
        this.parserConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
    }

    public List<ChunkRecord> chunk(Path javaFile, String relativePath) throws IOException {
        String source = Files.readString(javaFile);

        JavaParser parser = new JavaParser(parserConfig);
        ParseResult<CompilationUnit> parseResult = parser.parse(source);

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            String problems = parseResult.getProblems().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("; "));
            throw new IOException("Failed to parse " + relativePath + ": " + problems);
        }
        CompilationUnit cu = parseResult.getResult().get();

        String lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(javaFile).toMillis()).toString();

        List<String> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(imp.getNameAsString());
        }

        List<ChunkRecord> chunks = new ArrayList<>();

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            chunks.add(buildChunk(cls.toString(), relativePath, "ClassOrInterfaceDeclaration",
                    cls.getNameAsString(), imports,
                    cls.getBegin().map(p -> p.line).orElse(0),
                    cls.getEnd().map(p -> p.line).orElse(0),
                    lastModified));

            for (MethodDeclaration method : cls.findAll(MethodDeclaration.class)) {
                chunks.add(buildChunk(method.toString(), relativePath, "MethodDeclaration",
                        method.getNameAsString(), imports,
                        method.getBegin().map(p -> p.line).orElse(0),
                        method.getEnd().map(p -> p.line).orElse(0),
                        lastModified));
            }
        }

        return chunks;
    }

    private ChunkRecord buildChunk(String text, String filePath, String astNode, String name,
                                    List<String> dependencies, int startLine, int endLine,
                                    String lastModified) {
        String hash = hasher.hashContent(text);
        String chunkId = hash.substring(0, 16);
        List<String> tags = deriveStaticTags(text);
        return new ChunkRecord(chunkId, filePath, "java", startLine, endLine, astNode, name,
                dependencies, tags, text, hash, lastModified);
    }

    /**
     * Tags derived only from static signals (Architecture Plan §2: no LLM/embedding
     * clustering) — simple structural pattern checks against the chunk text.
     */
    private List<String> deriveStaticTags(String text) {
        List<String> tags = new ArrayList<>();
        if (text.contains("for (") || text.contains("for(") || text.contains("while (") || text.contains("while(")) {
            tags.add("has-loop");
        }
        if (text.contains("System.out") || text.contains("InputStream") || text.contains("OutputStream")
                || text.contains("Files.") || text.contains("Reader") || text.contains("Writer")) {
            tags.add("has-io-call");
        }
        if (text.contains("throw ") || text.contains("catch (") || text.contains("catch(")) {
            tags.add("has-error-handling");
        }
        return tags;
    }
}
