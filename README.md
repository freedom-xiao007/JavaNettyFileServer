# Netty 文件服务器
***
## 项目运行

```shell script
git clone xxxxxx

# 使用IDEA打开
# 运行HTTP文件文件服务器，入口文件： src/main/java/com/example/demo/http/Server
# 访问 http://127.0.0.1:8080/
```

## 功能介绍
### HTTP文件服务器
- 文件列表展示
- 文件下载

## 错误记录
### java.lang.NoClassDefFoundError: com/sun/activation/registries/LogSupport
使用下面的maven，idea自动添加的依赖有问题

```markdown
<dependency>
  <groupId>com.sun.activation</groupId>
  <artifactId>javax.activation</artifactId>
  <version>1.2.0</version>
</dependency>
```