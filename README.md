# tapp-uploadsrv
## セットアップ

[myskills](https://github.com/toshi0907/myskills) を submodule(`.myskills`)として取り込んでいます。ローカルでcloneした場合は、Claude Code を起動する前に submodule を初期化してください(未初期化だと `.claude/skills/` のsymlinkがリンク切れになります)。

```bash
git submodule update --init -- .myskills
```
