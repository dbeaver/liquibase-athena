# Liquibase Athena Extension

This is a Liquibase extension for connecting to an Amazon Athena database.

[Amazon Athena](https://docs.aws.amazon.com/athena/latest/ug/what-is.html) is an interactive query service that analyzes data directly in Amazon Simple Storage Service (Amazon S3) using standard SQL.

---
**NOTE**

Athena extension uses [Iceberg tables](https://docs.aws.amazon.com/athena/latest/ug/querying-iceberg.html) to manage [Liquibase Tracking Tables](https://docs.liquibase.com/concepts/tracking-tables/tracking-tables.html).

---

## Configuring the extension

These instructions will help you get the extension up and running on your local machine for development and testing purposes. This extension has a prerequisite of Liquibase core in order to use it. Liquibase core can be found at <https://www.liquibase.org/download>

### Pre-requisites

The following dependencies have to be available before using it:

* **[Github Token](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)** with ```read:package``` permissions.

### Liquibase CLI

Download [the latest released Liquibase extension](https://github.com/alvsanand/liquibase-athena/releases) `.jar` file and place it in the `liquibase/lib` install directory. If you want to use another location, specify the extension `.jar` and `athena-jdbc-*.jar` file in the `classpath` of your [liquibase.properties file](https://docs.liquibase.com/workflows/liquibase-community/creating-config-properties.html).

Comparing views of two schemas also needs [JSQLParser](https://github.com/JSQLParser/JSqlParser) 5.3 (`jsqlparser-5.3.jar`) on the same classpath. Maven resolves it as a dependency of the extension.

### Maven

First, add Github repository authentication to your ```pom.xml``` or ```settings.xml+```:

```xml  
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
    ...
    <servers>
      ...
        <server>
            <id>github</id>
            <username>{GITHUB_TOKEN}</username>
            <password>{GITHUB_TOKEN}</password>
        </server>
        ...
    </servers>
    ...
</settings>
```

Specify the Liquibase extension in the `<dependency>` section of ```pom.xml``` by adding the `liquibase:athena` dependency for the Liquibase plugin.

```xml
...
   <repositories>
      ...
      <repository>
         <id>github</id>
         <url>https://maven.pkg.github.com/alvsanand/liquibase-athena</url>
         <snapshots>
               <enabled>true</enabled>
         </snapshots>
      </repository>
      ...
   </repositories>
...
<plugins>
   ...
   <plugin>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-maven-plugin</artifactId>
      <version>4.3.2</version>
      <configuration>
         <propertyFile>liquibase.properties</propertyFile>
      </configuration>
      <dependencies>
         <dependency>
            <groupId>liquibase</groupId>
            <artifactId>athena</artifactId>
            <version>${liquibase-athena.version}</version>
         </dependency>
      </dependencies>
   </plugin>
...
</plugins>
...
```

### Extension Properties

Liquibase Athena extension needs to be configured to work so you must add the following properties:

```properties

# The S3 path where to store Liquibase tables.
liquibase.athena.liquibaseTableS3Location=s3://some_bucket/some_path
```
  
### Java call
  
```java
public class Application {
    public static void main(String[] args) {
        AthenaDatabase database = (AthenaDatabase) DatabaseFactory.getInstance().openDatabase(url, null, null, null, null);
        Liquibase liquibase = new Liquibase("liquibase/ext/changelog.generic.test.xml", new ClassLoaderResourceAccessor(), database);
        liquibase.update("");
    }
}
```

## Generated table DDL

Schema comparison between Athena databases uses the source `SHOW CREATE TABLE`
definition, preserving its S3 `LOCATION`, storage format, partition definitions,
and table properties. Only the created table's name is remapped to the target
database; the S3 location is kept unchanged. Reading the source definition requires
the corresponding Athena/Data Catalog permissions. A read failure stops change
generation rather than emitting an incomplete table definition.

Generic Liquibase table changes generate Athena external-table DDL with backtick-quoted
database and column names, without a catalog prefix or numeric display widths.
The generic table model does not contain S3 locations, storage formats, or partition
definitions. Add the required `LOCATION` and storage/partition clauses before executing
generated table DDL. This path does not recreate Iceberg tables; use their native DDL.

## Generated table changes

`addColumn`, `dropColumn` and `dropTable` generate Athena Hive DDL: `ALTER TABLE ... ADD COLUMNS (...)`,
`ALTER TABLE ... DROP COLUMN ...` and `DROP TABLE ...` with backtick-quoted names and no catalog prefix.
Column types reported with a length that Hive does not accept, such as `STRING(65535)`, are written without it.

Validation reports the changes Athena cannot apply instead of generating generic SQL for them:
`dropColumn` on a table which is known not to be Iceberg (the table format is read with `SHOW CREATE TABLE`;
a table which cannot be read yet is not rejected), added columns with constraints or default values,
and `modifyDataType`.

## Generated views

When a schema is compared with a schema of another name, a generated view reads the comparison schema instead of
the schema its definition was copied from: `SELECT id FROM src.orders` becomes `SELECT id FROM "tgt".orders`.
Only the relation names of the compared schemas are rewritten; relations of other schemas, expressions, aliases,
comments and literals keep their text.

Views are generated in the order which creates a view after the views it reads. A view which differs from the
reference one only by the names of the compared schemas is not replaced; the plain `diff` report still lists it
as changed, because the definition text differs.

Validation of a `createView` change reports a view which cannot be created:

* its definition cannot be rewritten safely, because the query cannot be parsed or qualifies columns with the name
  of an unaliased relation (`SELECT src.orders.id FROM src.orders`); give such relations explicit aliases;
* it reads such a view, or views read each other in a cycle.

The definition of such a view is left unchanged. Rejected views are saved as `createAthenaView` changes with
their validation error, so loading an XML, JSON or YAML changelog does not make them executable.
Supported views keep the standard `createView` format. A schema-only difference is ignored only when the
referenced relations have known, matching columns in both snapshots.

## Issue Tracking

Any issues can be logged in the [Github issue tracker](https://github.com/alvsanand/liquibase-athena/issues).

## License

This project is licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.html).
