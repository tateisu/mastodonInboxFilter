# mastodonInboxFilter

(English README is below.)

mastodon用のinbox SPAM filter です。
mastodonに手を入れずにスパムフィルタとして動作します。

## 仕組み

```
nginx
  ┗ POST .*/inbox$ => mastodonInboxFilter => mastodon
  ┗ other => mastodon 
```
- nginx を設定して、外部から来たリクエストが POST で URL末尾が /inbox だった場合はリクエストを mastodonInboxFilter に中継します。
- それ以外のリクエストは普通にmastodon に中継します。
- mastodonInboxFilter は inboxに来たリクエストがSPAMかどうか判定します。
- スパムではないと判断したらmastodonに中継します。
- スパムだと判断したらしれっと 202 Accepted を返します。

## mastodonInboxFilter のセットアップ

### ビルド
- Java 17 以上が必要

```
./gradlew shadowJar
LINE=`ls -1t build/libs/mastodonInboxFilter-*-all.jar|head -n 1`
SRCJAR=`echo -n $LINE`
DSTJAR="mastodonInboxFilter.jar"
cp "$SRCJAR" "$DSTJAR"
```
依存関係全部入りのJARが生成されます。
JARファイルとJavaと設定ファイルがあれば起動可能です。

### コマンドラインオプション
```
$ java -jar mastodonInboxFilter.jar --help
Usage: java -jar mastodonInboxFilter.jar options_list
Arguments:
    configPath [config.json5] -> config file (optional) { String }
Options:
    --configTest -> config test only
    --help, -h -> Usage info
    
```

### 設定ファイル
まず設定ファイルの雛形をコピーします。
```
cp sample.config.json5 config.json5
```
コピーした `config.json5` を編集します。
- 待機ホスト、待機ポート、リダイレクト先の変更は必須です。

### 起動
```
nohup java -jar mastodonInboxFilter.jar >>./mastodonInboxFilter.log 2>&1 &
```

### 終了
```
kill `cat mastodonInboxFilter.pid`
```
サーバはTERMシグナルを受け取ると後処理の後に終了します。

### ログ
起動時に指定した mastodonInboxFilter.log に出力されます。

ログの例：
```
$ tail -f mastodonInboxFilter.log
2024-02-22T10:22:59 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:22:59 WARN  APStatus - 20240222-102259.366 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972514443600421/activity
2024-02-22T10:23:04 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:23:04 WARN  APStatus - 20240222-102304.996 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972514831659095/activity
2024-02-22T10:27:40 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:27:40 WARN  APStatus - 20240222-102740.514 root.type is Delete. id=https://mastodon.social/users/DrkPhnx0991#delete
2024-02-22T10:31:31 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:31:31 WARN  APStatus - 20240222-103131.443 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972548029757946/activity
2024-02-22T10:34:50 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:34:50 INFO  SpamCheck - NG <word: https://ctkpaarr.org/> https://cmm.fyi/@w15e4pzlx4/111972560958939975 https://ctkpaarr.org/
```

## nginx のセットアップ
`location @proxy`の中でproxy_passを指定してる箇所を変更します。

```
    set $match "A";
    if ( $request_method = POST ){
       set $match "${match}B";
    }
    if ( $uri ~ "/inbox$" ){
       set $match "${match}C";
    }
    if ( $match = "ABC" ){
        # mastodonInboxFilter に中継する
        proxy_pass http://XXX.XXX.XXX.XXX:XXXX;
    }
    if ( $match != "ABC" ){
        # mastodonサーバに中継する
        proxy_pass http://YYY.YYY.YYY.YYY:YYYY;
    }
```
変更したら `sudo service nginx configtest` して大丈夫そうなら `sudo service nginx restart` します。

====
# README(English)

## What is this
- This is a SPAM filter for Mastodon inbox works as HTTP(s) reverse proxy server.
- No need to modify Mastodon's source code and setting.

## How it works

```
nginx
  ┗ POST .*/inbox$ => mastodonInboxFilter => mastodon
  ┗ other => mastodon 
```
- Configure nginx that proxy the `POST .*inbox$` request to mastodonInboxFilter.
- Other requests are proxy to mastodon server normally.
- mastodonInboxFilter determines whether the request is SPAM or not.
- If the requests is not SPAM, those are proxy to mastodon server.
- If the requests is detected as SPAM, mastodonInboxFilter returns `202 Accepted` without proxy to mastodon server.

## Setup mastodonInboxFilter

### Build
- Java 17+ required.

```
./gradlew shadowJar
LINE=`ls -1t build/libs/mastodonInboxFilter-*-all.jar|head -n 1`
SRCJAR=`echo -n $LINE`
DSTJAR="mastodonInboxFilter.jar"
cp "$SRCJAR" "$DSTJAR"
```
Generated JAR file will contains all dependencies.
You can run it if you have `mastodonInboxFilter.jar`, `java`, and `config.json5`.

### Command line options
```
$ java -jar mastodonInboxFilter.jar --help
Usage: java -jar mastodonInboxFilter.jar options_list
Arguments:
    configPath [config.json5] -> config file (optional) { String }
Options:
    --configTest -> config test only
    --help, -h -> Usage info
    
```

### configuration file 
First, copy the configuration file from sample.
```
cp sample.config.json5 config.json5
```
Then edit `config.json5` file. 
- It is mandatory to change the standby host, standby port, and redirect destination.

### Start server
```
nohup java -jar mastodonInboxFilter.jar >>./mastodonInboxFilter.log 2>&1 &
```

### Stop server
```
kill `cat mastodonInboxFilter.pid`
```
When the server receives the TERM signal, it exits after some cleanup.

### Log
Log is output to `mastodonInboxFilter.log` specified at startup.

example:
```
$ tail -f mastodonInboxFilter.log
2024-02-22T10:22:59 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:22:59 WARN  APStatus - 20240222-102259.366 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972514443600421/activity
2024-02-22T10:23:04 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:23:04 WARN  APStatus - 20240222-102304.996 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972514831659095/activity
2024-02-22T10:27:40 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:27:40 WARN  APStatus - 20240222-102740.514 root.type is Delete. id=https://mastodon.social/users/DrkPhnx0991#delete
2024-02-22T10:31:31 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:31:31 WARN  APStatus - 20240222-103131.443 root.type is Announce. id=https://taruntarun.net/users/mayaeh/statuses/111972548029757946/activity
2024-02-22T10:34:50 INFO  InboxProxy - inboxProxy POST /inbox
2024-02-22T10:34:50 INFO  SpamCheck - NG <word: https://ctkpaarr.org/> https://cmm.fyi/@w15e4pzlx4/111972560958939975 https://ctkpaarr.org/
```

## Setup nginx configuration
Change the part where proxy_pass is specified in `location @proxy`.

```
    set $match "A";
    if ( $request_method = POST ){
       set $match "${match}B";
    }
    if ( $uri ~ "/inbox$" ){
       set $match "${match}C";
    }
    if ( $match = "ABC" ){
        # mastodonInboxFilter に中継する
        proxy_pass http://XXX.XXX.XXX.XXX:XXXX;
    }
    if ( $match != "ABC" ){
        # mastodonサーバに中継する
        proxy_pass http://YYY.YYY.YYY.YYY:YYYY;
    }
```
After making changes, run `sudo service nginx configtest` to validate config and if everything looks okay, run `sudo service nginx restart`.
