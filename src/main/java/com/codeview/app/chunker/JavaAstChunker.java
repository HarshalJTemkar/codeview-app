package com.codeview.app.chunker;

import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import com.github.javaparser.StaticJavaParser;
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
 */
@Component
public class JavaAstChunker {

    private final MerkleHasher hasher;

    public JavaAstChunker(MerkleHasher hasher) {
        this.hasher = hasher;
    }

    public List<ChunkRecord> chunk(Path javaFile, String relativePath) throws IOException {
        String source = Files.readString(javaFile);
        CompilationUnit cu = StaticJavaParser.parse(source);
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
