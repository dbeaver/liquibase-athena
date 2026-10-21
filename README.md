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

Specify the Liquibase extension in the `<dependency>` section of ```pom.xml``` by adding the `org.liquibase.ext` dependency for the Liquibase plugin.

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
            <groupId>org.liquibase.ext</groupId>
            <artifactId>liquibase-athena</artifactId>
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

Generic Liquibase table changes generate Athena external-table DDL with backtick-quoted
database and column names, without a catalog prefix or numeric display widths.
The generic table model does not contain S3 locations, storage formats, or partition
definitions. Add the required `LOCATION` and storage/partition clauses before executing
generated table DDL. This path does not recreate Iceberg tables; use their native DDL.

## Issue Tracking

Any issues can be logged in the [Github issue tracker](https://github.com/alvsanand/liquibase-athena/issues).

## License

This project is licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.html).
