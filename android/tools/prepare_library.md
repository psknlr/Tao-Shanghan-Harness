# 生成 VIP 全量古籍庫包（library-pack/）

VIP APK 的全量古籍庫（803 部/317MB）不入 git。構建帶庫 VIP 包前執行：

```bash
cd ../backend
python3 -c "from hermes_shanghan.corpus import library; library.fetch(verbose=True)"
# 下載 https://jicheng.tw/files/jcw/book-20180111.7z（sha256 固定校驗）
# → 解壓審查 → 編目 catalog.json → 字符索引 charindex.json

mkdir -p ../android/library-pack
cp data/library/catalog.json data/library/charindex.json ../android/library-pack/
cp -r data/library/books ../android/library-pack/
```

`library-pack/` 存在時 `assembleVipDebug/Release` 自動打入
`assets/library/`；不存在時 VIP 正常構建（古籍庫界面顯示未內置）。

> 注意：構建機必須用 UTF-8 locale 運行 Gradle（`LC_ALL=C.UTF-8`），
> 否則 JVM 在 POSIX locale 下無法讀取中文書名目錄，
> `copyVipAssets` 會報 "Failed to create MD5 hash … does not exist"。
