Node database
=============

Default in-memory database
--------------------------
By default, nodes store their data in an H2 database. See :doc:`node-database-access-h2`.

PostgreSQL
----------
Nodes can also be configured to use PostgreSQL 9.6, using PostgreSQL JDBC Driver 42.1.4.

.. warning:: This is an experimental community contribution. The Corda continuous integration pipeline does not run unit 
   tests or integration tests of this feature.

Configuration
~~~~~~~~~~~~~
Here is an example node configuration for PostgreSQL:

.. sourcecode:: groovy

    dataSourceProperties = {
        dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
        dataSource.url = "jdbc:postgresql://[HOST]:[PORT]/[DATABASE]"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
    }

Note that:
* Database schema name can be set in JDBC URL string e.g. currentSchema=myschema
* Database schema name must either match the ``dataSource.user`` value to end up
  on the standard schema search path according to the
  `PostgreSQL documentation <https://www.postgresql.org/docs/9.3/static/ddl-schemas.html#DDL-SCHEMAS-PATH>`_, or
  the schema search path must be set explicitly for the user.

SQLServer
---------
Nodes also have untested support for Microsoft SQL Server 2017, using Microsoft JDBC Driver 6.2 for SQL Server.

.. warning:: This is an experimental community contribution, and is currently untested. We welcome pull requests to add
   tests and additional support for this feature.

Configuration
~~~~~~~~~~~~~
Here is an example node configuration for SQLServer:

.. sourcecode:: groovy

    dataSourceProperties = {
        dataSourceClassName = "com.microsoft.sqlserver.jdbc.SQLServerDataSource"
        dataSource.url = "jdbc:sqlserver://[HOST]:[PORT];databaseName=[DATABASE_NAME]"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
    }
    jarDirs = ["[FULL_PATH]/sqljdbc_6.2/enu/"]

Note that:

* Ensure the directory referenced by jarDirs contains only one JDBC driver JAR file; by the default,
  sqljdbc_6.2/enu/contains two JDBC JAR files for different Java versions.
