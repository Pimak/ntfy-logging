# BOM

`ntfy-bom` pins every published `ntfy-logging` artifact to one version. Import it once and the
version disappears from each individual dependency — including the ones a transitive path pulls
in, which is where a mixed-version tree usually starts.

## Import it

=== "Maven"

    ```xml
    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>io.github.pimak</groupId>
          <artifactId>ntfy-bom</artifactId>
          <version>2.0.0</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>

    <dependencies>
      <dependency>
        <groupId>io.github.pimak</groupId>
        <artifactId>ntfy-logback</artifactId>
      </dependency>
    </dependencies>
    ```

=== "Gradle"

    ```kotlin
    dependencies {
        implementation(platform("io.github.pimak:ntfy-bom:2.0.0"))
        implementation("io.github.pimak:ntfy-logback")
    }
    ```

Note the absent `<version>` on `ntfy-logback`: that is the whole point. Bump the BOM and every
managed artifact moves together.

## What it manages

The seven artifacts you would ever write in a build file:

| Artifact | Guide |
|----------|-------|
| `ntfy-core` | [Core](core.md) |
| `ntfy-jul` | [java.util.logging](jul.md) |
| `ntfy-logback` | [Plain Logback](logback.md) |
| `ntfy-log4j2` | [Plain Log4j2](log4j2.md) |
| `ntfy-spring-boot-starter` | [Spring Boot](spring-boot.md) |
| `ntfy-micronaut` | [Micronaut](micronaut.md) |
| `ntfy-quarkus-runtime` | [Quarkus](quarkus.md) |

## What it deliberately leaves out

Two published artifacts are absent, and their absence is a statement:

- **`ntfy-quarkus`** is an aggregator (`packaging=pom`). It exists to group the extension's two
  halves; nothing depends on it.
- **`ntfy-quarkus-deployment`** is resolved by the Quarkus build from the runtime artifact's own
  metadata. Declaring it by hand is a mistake, and managing it here would invite exactly that.

Both are still released to Central and still guarded against binary-incompatible change. Being
outside the BOM means "you don't depend on this", not "this isn't published".

## Do you need it?

If you use a single module and write its version once, no — the BOM buys you nothing. It earns its
place when you depend on more than one artifact, or when a starter drags a second one in
transitively: that is when versions drift apart without anyone deciding they should.
