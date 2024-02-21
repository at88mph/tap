# cadc-tap-tmp

Simple library to provide plugins that implement both the ResultStore (TAP-async 
result storage) and UWSInlineContentHandler (inline TAP_UPLOAD support) interfaces. 
Two implementations are provided: `org.opencadc.tap.tmp.TempStorageManager` uses a configurable
local directory in the filesystem, `org.opencadc.tap.tmp.HttpStorageManager` uses an 
external HTTP service to store and deliver files.

In addition, a third implementation `org.opencadc.tap.tmp.DelegatingStorageManager` can be
configured in services; this implementation can be configured to load and use one of the
other two classes above.

## configuration

### cadc-tap-tmp.properties
This configuration file needs to be accessible in the `System.getProperty("user.home")/config` folder.

For the local filesystem using `TempStorageManager`:
```properties
# specify implementation in case the DelegatingStorageManager is used
org.opencadc.tap.tmp.StorageManager = org.opencadc.tap.tmp.TempStorageManager

# TempStorageManager config
org.opencadc.tap.tmp.TempStorageManager.baseURL = {base URL for the tmp files}
org.opencadc.tap.tmp.TempStorageManager.baseStorageDir = {local directory for tmp files}
```
For the TempStorageManager, an additional servlet must be deployed in the TAP 
service to [Enable Retrieval](#enable-retrieval) and the _baseURL_ must include
the path to that servlet.

For the external http service using `HttpStorageManager`:
```properties
# specify implementation in case the DelegatingStorageManager is used
org.opencadc.tap.tmp.StorageManager = org.opencadc.tap.tmp.HttpStorageManager

# HttpStorageManager config
org.opencadc.tap.tmp.HttpStorageManager.baseURL = {base URL for tmp files}
org.opencadc.tap.tmp.HttpStorageManager.certificate = {certificate file name}
```
For the HttpStorageManager, the result will be PUT to that same URL and requires 
an X509 client certificate to authenticate. The certificate is located in 
{user.home}/.ssl/{certificate file name}.

In addition to the configured result storage, the HttpStorageManager ResultStore 
implementation supports a user-specified job parameter `DEST={uri}` which will direct 
output of async queries to the specified URI. This can be a VOSpace URI 
(`vos://{authority}~{service}/{path}`) or an `https` URL that accepts PUT and 
supports GET. When DEST is used, the caller's credentials are used instead of the 
configured certificate.

In both cases, result files will be retrievable from {baseURL}/{result_filename} 
(unless DEST was used).


### Enable storage
To enable temporary uploads to disk, configure the `InlineContentHandler` in both 
the TAP-sync and TAP-async servlets to load one of the plugin classes:

In the `web.xml`:

```xml
<servlet>
  ...
  <init-param>
    <param-name>ca.nrc.cadc.rest.InlineContentHandler</param-name>
    <param-value>org.opencadc.tap.tmp.TempStorageManager</param-value>
  </init-param>
  ...
</servlet>
```

The `ResultStore` implementation is configured in the TAP service's
`PluginFactory.properties`, e.g.:

```properties
ca.nrc.cadc.tap.ResultStore = org.opencadc.tap.tmp.TempStorageManager
```

### Enable retrieval for TempStorageManager
To enable retrieval of the stored file, such as an asynchronous query result, 
a new endpoint will be required using the `TempStorageInitAction` and
`TempStorageGetAction`:

```xml
    <servlet>
        <servlet-name>TempStorageServlet</servlet-name>
        <servlet-class>ca.nrc.cadc.rest.RestServlet</servlet-class>
    
        <!-- Optional init parameter to validate configuration. -->
        <init-param>
            <param-name>init</param-name>
            <param-value>org.opencadc.tap.tmp.TempStorageInitAction</param-value>
        </init-param>

        <init-param>
            <param-name>get</param-name>
            <param-value>org.opencadc.tap.tmp.TempStorageGetAction</param-value>
        </init-param>
        <load-on-startup>3</load-on-startup>
    </servlet>
    
    <servlet-mapping>
        <servlet-name>TempStorageServlet</servlet-name>
        <url-pattern>/stuff-to-keep-and-serve/*</url-pattern>
    </servlet-mapping>
```
The `baseURL` in `cadc-tap-tmp.properties` must include the path component used in the above
servlet-mapping, e.g. `https://example.net/tap/stuff-to-keep-and-serve`.
