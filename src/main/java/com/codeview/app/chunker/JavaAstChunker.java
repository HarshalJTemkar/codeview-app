package com.codeview.app.chunker;

import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Structure-aware chunker for Java source: parses an AST via JavaParser and
 * emits one chunk per class/interface and one chunk per method, matching the
 * source doc's "self-contained, semantically coherent" chunk goal (functions,
 * classes) for the Java-first scope confirmed for this build.
 *
 * <p><b>Deviation from the source design doc:</b> it specifies Tree-sitter.
 * JavaParser is used here instead because Tree-sitter's Java grammar has no
 * straightforward Maven Central artifact to depend on — a stated deviation,
 * not a silent substitution. A fixed-line/brace fallback for unsupported
 * languages is not implemented; only Java is in scope per your confirmed
 * decision.
 *
 * <p><b>Bug fix (this build):</b> earlier versions used {@code
 * StaticJavaParser.parse()}, whose default language level rejects records,
 * pattern-matching {@code instanceof}, and text blocks. Fixed by giving each
 * call its own {@link JavaParser} configured with {@code BLEEDING_EDGE},
 * which also removes the thread-safety risk of relying on
 * {@code StaticJavaParser}'s shared static configuration under the parallel
 * worker pool (Architecture Plan §12).
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

    /**
     * Parses one Java source file and returns one chunk per class/interface
     * declaration plus one chunk per method within each of those classes.
     *
     * @param javaFile     absolute path to the file on disk
     * @param relativePath path relative to the source root, stored as each
     *                     chunk's {@code file_path} and used to rebuild the
     *                     directory/file/symbol tree later
     */
    public List<ChunkRecord> chunk(Path javaFile, String relativePath) throws IOException {
        CompilationUnit compilationUnit = parse(javaFile, relativePath);
        List<String> imports = extractImportNames(compilationUnit);
        String lastModified = readLastModified(javaFile);

        return compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(classDecl -> chunkClassAndItsMethods(classDecl, relativePath, imports, lastModified))
                .toList();
    }

    /**
     * Parses raw file content into a JavaParser AST, using a fresh {@link
     * JavaParser} instance per call (see class Javadoc for why this isn't
     * {@code StaticJavaParser}). Throws with the parser's own problem list
     * attached rather than swallowing the detail — a caller retrying this
     * file needs to know *why* it failed, not just that it did.
     */
    private CompilationUnit parse(Path javaFile, String relativePath) throws IOException {
        String source = Files.readString(javaFile);
        JavaParser parser = new JavaParser(parserConfig);
        ParseResult<CompilationUnit> result = parser.parse(source);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("; "));
            throw new IOException("Failed to parse " + relativePath + ": " + problems);
        }
        return result.getResult().get();
    }

    /** Extracts every import's fully-qualified name, in source order, as plain strings. */
    private List<String> extractImportNames(CompilationUnit compilationUnit) {
        return compilationUnit.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();
    }

    /** Reads a file's last-modified timestamp as an ISO-8601 string, for the chunk's `last_modified` field. */
    private String readLastModified(Path file) throws IOException {
        return Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString();
    }

    /**
     * Produces one chunk for the class/interface itself, followed by one
     * chunk per method declared directly on it. Returned as a stream so
     * {@link #chunk} can flatMap across every class in the file without an
     * intermediate mutable list per class.
     */
    private Stream<ChunkRecord> chunkClassAndItsMethods(ClassOrInterfaceDeclaration classDecl,
                                                          String relativePath, List<String> imports,
                                                          String lastModified) {
        ChunkRecord classChunk = toChunkRecord(classDecl, "ClassOrInterfaceDeclaration",
                classDecl.getNameAsString(), relativePath, imports, lastModified);

        Stream<ChunkRecord> methodChunks = classDecl.findAll(MethodDeclaration.class).stream()
                .map(method -> toChunkRecord(method, "MethodDeclaration",
                        method.getNameAsString(), relativePath, imports, lastModified));

        return Stream.concat(Stream.of(classChunk), methodChunks);
    }

    /** Builds one ChunkRecord from an AST node, hashing its source text and deriving its static tags. */
    private ChunkRecord toChunkRecord(Node node, String astNode, String name, String relativePath,
                                       List<String> imports, String lastModified) {
        String text = node.toString();
        String hash = hasher.hashContent(text);
        String chunkId = hash.substring(0, 16);

        return ChunkRecord.builder()
                .chunkId(chunkId)
                .filePath(relativePath)
                .language("java")
                .lines(startLineOf(node), endLineOf(node))
                .astNode(astNode)
                .name(name)
                .dependencies(imports)
                .tags(deriveStaticTags(text))
                .text(text)
                .hash(hash)
                .lastModified(lastModified)
                .build();
    }

    private int startLineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private int endLineOf(Node node) {
        return node.getEnd().map(p -> p.line).orElse(0);
    }

    /**
     * Tags derived only from static signals (Architecture Plan §2: no LLM/embedding
     * clustering) — simple structural pattern checks against the chunk text.
     * Each tag is independent, so this reads as a small pipeline of
     * predicate-checks rather than a chain of if-statements.
     */
    private List<String> deriveStaticTags(String text) {
        record TagRule(String tag, java.util.function.Predicate<String> matches) {
        }

        List<TagRule> rules = List.of(
                new TagRule("has-loop", t -> t.contains("for (") || t.contains("for(")
                        || t.contains("while (") || t.contains("while(")),
                new TagRule("has-io-call", t -> t.contains("System.out") || t.contains("InputStream")
                        || t.contains("OutputStream") || t.contains("Files.")
                        || t.contains("Reader") || t.contains("Writer")),
                new TagRule("has-error-handling", t -> t.contains("throw ")
                        || t.contains("catch (") || t.contains("catch("))
        );

        return rules.stream()
                .filter(rule -> rule.matches().test(text))
                .map(TagRule::tag)
                .toList();
    }
}
