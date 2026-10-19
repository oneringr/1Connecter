# 项目约定

## 签名、提交与发布

- Android 发布包使用 `D:\存档\安卓开发` 目录中的签名文件和配置。签名材料保留在仓库外，不提交密钥或密码。
- 通过 GitHub CLI（`gh`）完成 GitHub 提交与发布流程；本地版本记录使用 Git，并使用 `gh` 配置的 GitHub 身份推送。
- 每次提交代码时同步发布对应的 GitHub Release，使提交、版本标签、Release 说明和签名 APK 保持一致。
- README 和 Release 文案参考 [Best-README-Template](https://github.com/othneildrew/Best-README-Template) 的结构和表达方式，按本项目实际功能、安装方式、变更与验证结果撰写，不保留模板占位内容。
