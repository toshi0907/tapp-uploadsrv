# tapp-uploadsrv

Android端末上でHTTPサーバーを常駐させ、同一Wi-Fi内のブラウザから写真・動画をアップロードするアプリ「アップロードサーバー」。

## APKの入手

- 最新版(mainブランチ): [Releases の `latest`](../../releases/tag/latest) から `uploadsrv-<ビルド番号>.apk` をダウンロードしてインストール
- 作業ブランチ/PRのビルド: GitHub Actions の各実行の Artifacts から取得(90日で失効)

デバッグ署名鍵(`app/debug.keystore`)をリポジトリに固定しているため、ビルドが変わっても上書きインストールできます。

## アプリ内アップデート

- アプリ起動時と、バックグラウンドで6時間ごとに Releases の `latest` を確認し、本文の `build: <番号>` と自身のビルド番号を比較します
- 新しいビルドがあると通知します(同じビルドの通知は1回のみ)。通知をタップするとAPKをダウンロードし、インストーラを起動します
- 画面の「アプリの更新」からも確認・ダウンロード・インストールできます
- 初回は「不明なアプリのインストール」の許可が必要です(画面の案内から設定を開けます)

## セットアップ

[myskills](https://github.com/toshi0907/myskills) を submodule(`.myskills`)として取り込んでいます。ローカルでcloneした場合は、Claude Code を起動する前に submodule を初期化してください(未初期化だと `.claude/skills/` のsymlinkがリンク切れになります)。

```bash
git submodule update --init -- .myskills
```
