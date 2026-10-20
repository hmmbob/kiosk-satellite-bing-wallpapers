# Contributing

Bug reports and focused pull requests are welcome. For larger features or changes
to the plugin's behavior, open an issue first so we can agree on the scope before
you spend time implementing it.

This is a community project maintained in spare time. There is no guaranteed
response time, and submitting a pull request does not guarantee acceptance.

## Report a problem

Search existing issues first, then use the bug report form. Include the plugin
and KS versions, device details, what happened and how to reproduce it.
Screenshots and relevant log excerpts are helpful when available, but are not
required for every report. Never share passwords, tokens or configuration exports.

## Propose a change

- Keep each pull request focused on one problem.
- Use English for code comments, documentation and plugin messages.
- Preserve saved setting values and plugin/mode IDs unless a change has been
  discussed and includes an upgrade plan.
- Follow the existing code style and retain third-party notices.
- Add or update tests when behavior changes. Explain any checks you could not run.
- Do not include downloaded Bing photos, credentials or generated build files.

See [developer notes](docs/DEVELOPMENT.md) for the toolchain and implementation
details. Before submitting code changes, run:

```sh
python tools/test.py
python tools/build.py --android-platform 35
```

If you change `assets/texts.json`, first regenerate the Java text catalogue:

```sh
python tools/texts.py --write
```

CI tests and builds pull requests. It does not publish a release. A maintainer
reviews changes and decides whether to merge them; releases are published
separately through the Release workflow.

Contributions are provided under the project's Apache-2.0 license. Third-party
assets retain their own rights and notices; see [credits](assets/NOTICE.txt).
