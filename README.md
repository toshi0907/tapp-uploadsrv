# tapp-uploadsrv

Android端末上でHTTPサーバーを常駐させ、同一Wi-Fi内のブラウザから写真・動画をアップロードするアプリ「アップロードサーバー」。

## APKの入手

- 最新版(mainブランチ): [Releases の `latest`](../../releases/tag/latest) から `uploadsrv-<ビルド番号>.apk` をダウンロードしてインストール
- 作業ブランチ/PRのビルド: GitHub Actions の各実行の Artifacts から取得(90日で失効)

デバッグ署名鍵(`app/debug.keystore`)をリポジトリに固定しているため、ビルドが変わっても上書きインストールできます。

## セットアップ

[myskills](https://github.com/toshi0907/myskills) を submodule(`.myskills`)として取り込んでいます。ローカルでcloneした場合は、Claude Code を起動する前に submodule を初期化してください(未初期化だと `.claude/skills/` のsymlinkがリンク切れになります)。

```bash
git submodule update --init -- .myskills
```
