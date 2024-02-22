# mastodonInboxFilter

mastodon用のinbox SPAM filter です。
mastodonに手を入れずにスパムフィルタとして動作します。

## 仕組み

```
nginx
  ┗ POST /inbox$ => mastodonInboxFilter => mastodon
  ┗ other => mastodon 
```
- nginx を設定して、外部から来たリクエストが POST で URL末尾が /inbox だった場合はリクエストを mastodonInboxFilter に中継します。
- それ以外のリクエストは普通にmastodon に中継します。
- mastodonInboxFilter は inboxに来たリクエストがSPAMかどうか判定します。
- スパムではないと判断したらmastodonに中継します。
- スパムだと判断したらしれっと 202 Accepted を返します。

## mastodonInboxFilter の設定と起動と終了

### ビルド
- Java 17 以上が必要

```
./gradlew shadowJar
LINE=`ls -1t build/libs/mastodonInboxFilter-*-all.jar|head -n 1`
SRCJAR=`echo -n $LINE`
DSTJAR="mastodonInboxFilter.jar"
cp "$SRCJAR" "$DSTJAR"
```

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

## nginx の設定
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

