# MonkeyCI Github Plugin

This is a Clojure lib that provides functions to interact with the Github
API from a [MonkeyCI](https://monkeyci.com) build.

## Usage

In your `.monkeyci` directory, add the dependency to your `deps.edn`:
```clojure
{:deps {com.monkeyci/plugin-github {:mvn/version "0.1.0-SNAPSHOT"}}}
```

Then, in your `build.clj`, require the namespace, and then you can add one
of the provided build jobs:
```clojure
(require '[monkey.ci.plugin.github :as gh])
```

## Provided Jobs

The jobs currently provided are these:

### release-job

Creates a release according to the current build settings.  This job will
only be executed if the build is triggered from a tag.  It will use the
tag name as release name, formatted using the configured format string.

```clojure
;; Job that will create a release in Github for the commit tag,
;; formatting the tag by prefixing it with a `v`.
(gh/release-job {:name-format "v%s"})
```

This job in turn invokes the `create-release!` function in Github.  This call
requires some sort of authentication to the Github API, for which it will
automatically fetch the `github-token` build parameter, or you can provide your
own `:token` in the job configuration.  If no token is provided, the job will
fail.

The jobs usually run inside the build script itself, so they don't need
to start a new container, and are very fast.

The `release-job` function accepts these options:
|Option|Default|Meaning|
|---|---|---|
|`name-format`|`v%s`|How the release name is formatted using the tag name|
|`dependencies`||Optional dependencies for the job|
|`org`|Build org|The organisation to use when calling Github.  Defaults to the organisation extracted from the repository url.|
|`repo`|Build repo|The repository to create the release on.  Defaults to the configured MonkeyCI repo.|
|`desc`||Optional description for the release|
|`token`|`github-token` param or `GITHUB_TOKEN` env|The token to use to authenticate to Github.|

## TODO

 - As soon as MonkeyCI supports it, we should allow invoking the MonkeyCI API to create the release.
 - Allow using app config (client id and secret) to authenticate to Github.

## License

Copyright (c) 2024 by [Monkey Projects](https://www.monkey-projects.be)

[MIT License](LICENSE)