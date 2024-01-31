using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Semmle.Util;
using Semmle.Util.Logging;
using Semmle.Autobuild.Shared;
using Newtonsoft.Json.Linq;

namespace Semmle.Autobuild.CSharp
{
    /// <summary>
    /// A build rule where the build command is of the form "dotnet build".
    /// Currently unused because the tracer does not work with dotnet.
    /// </summary>
    internal class DotNetRule : IBuildRule<CSharpAutobuildOptions>
    {
        public readonly List<IProjectOrSolution> FailedProjectsOrSolutions = new();

        /// <summary>
        /// A list of projects which are incompatible with DotNet.
        /// </summary>
        public IEnumerable<Project<CSharpAutobuildOptions>> NotDotNetProjects { get; private set; }

        public DotNetRule() => NotDotNetProjects = new List<Project<CSharpAutobuildOptions>>();

        public BuildScript Analyse(IAutobuilder<CSharpAutobuildOptions> builder, bool auto)
        {
            if (!builder.ProjectsOrSolutionsToBuild.Any())
                return BuildScript.Failure;

            if (auto)
            {
                NotDotNetProjects = builder.ProjectsOrSolutionsToBuild
                    .SelectMany(p => Enumerators.Singleton(p).Concat(p.IncludedProjects))
                    .OfType<Project<CSharpAutobuildOptions>>()
                    .Where(p => !p.DotNetProject);
                var notDotNetProject = NotDotNetProjects.FirstOrDefault();

                if (notDotNetProject is not null)
                {
                    builder.Log(Severity.Info, "Not using .NET Core because of incompatible project {0}", notDotNetProject);
                    return BuildScript.Failure;
                }

                builder.Log(Severity.Info, "Attempting to build using .NET Core");
            }

            return WithDotNet(builder, (dotNetPath, environment) =>
                {
                    var ret = GetInfoCommand(builder.Actions, dotNetPath, environment);
                    foreach (var projectOrSolution in builder.ProjectsOrSolutionsToBuild)
                    {
                        var cleanCommand = GetCleanCommand(builder.Actions, dotNetPath, environment);
                        cleanCommand.QuoteArgument(projectOrSolution.FullPath);
                        var clean = cleanCommand.Script;

                        var restoreCommand = GetRestoreCommand(builder.Actions, dotNetPath, environment);
                        restoreCommand.QuoteArgument(projectOrSolution.FullPath);
                        var restore = restoreCommand.Script;

                        var build = GetBuildScript(builder, dotNetPath, environment, projectOrSolution.FullPath);

                        ret &= BuildScript.Try(clean) & BuildScript.Try(restore) & BuildScript.OnFailure(build, ret =>
                        {
                            FailedProjectsOrSolutions.Add(projectOrSolution);
                        });
                    }
                    return ret;
                });
        }

        /// <summary>
        /// Returns a script that attempts to download relevant version(s) of the
        /// .NET Core SDK, followed by running the script generated by <paramref name="f"/>.
        ///
        /// The arguments to <paramref name="f"/> are the path to the directory in which the
        /// .NET Core SDK(s) were installed and any additional required environment
        /// variables needed by the installed .NET Core (<code>null</code> when no variables
        /// are needed).
        /// </summary>
        public static BuildScript WithDotNet(IAutobuilder<AutobuildOptionsShared> builder, Func<string?, IDictionary<string, string>?, BuildScript> f)
        {
            var installDir = builder.Actions.PathCombine(FileUtils.GetTemporaryWorkingDirectory(builder.Actions.GetEnvironmentVariable, builder.Options.Language.UpperCaseName, out var _), ".dotnet");
            var installScript = DownloadDotNet(builder, installDir);
            return BuildScript.Bind(installScript, installed =>
            {
                Dictionary<string, string>? env;
                if (installed == 0)
                {
                    // The installation succeeded, so use the newly installed .NET Core
                    var path = builder.Actions.GetEnvironmentVariable("PATH");
                    var delim = builder.Actions.IsWindows() ? ";" : ":";
                    env = new Dictionary<string, string>{
                            { "DOTNET_MULTILEVEL_LOOKUP", "false" }, // prevent look up of other .NET Core SDKs
                            { "DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "true" },
                            { "PATH", installDir + delim + path }
                        };
                }
                else
                {
                    installDir = null;
                    env = null;
                }

                return f(installDir, env);
            });
        }

        /// <summary>
        /// Returns a script that attempts to download relevant version(s) of the
        /// .NET Core SDK, followed by running the script generated by <paramref name="f"/>.
        ///
        /// The argument to <paramref name="f"/> is any additional required environment
        /// variables needed by the installed .NET Core (<code>null</code> when no variables
        /// are needed).
        /// </summary>
        public static BuildScript WithDotNet(IAutobuilder<AutobuildOptionsShared> builder, Func<IDictionary<string, string>?, BuildScript> f)
            => WithDotNet(builder, (_1, env) => f(env));

        /// <summary>
        /// Returns a script for downloading relevant versions of the
        /// .NET Core SDK. The SDK(s) will be installed at <code>installDir</code>
        /// (provided that the script succeeds).
        /// </summary>
        private static BuildScript DownloadDotNet(IAutobuilder<AutobuildOptionsShared> builder, string installDir)
        {
            if (!string.IsNullOrEmpty(builder.Options.DotNetVersion))
                // Specific version supplied in configuration: always use that
                return DownloadDotNetVersion(builder, installDir, builder.Options.DotNetVersion);

            // Download versions mentioned in `global.json` files
            // See https://docs.microsoft.com/en-us/dotnet/core/tools/global-json
            var installScript = BuildScript.Success;
            var validGlobalJson = false;
            foreach (var path in builder.Paths.Select(p => p.Item1).Where(p => p.EndsWith("global.json", StringComparison.Ordinal)))
            {
                string version;
                try
                {
                    var o = JObject.Parse(File.ReadAllText(path));
                    version = (string)(o?["sdk"]?["version"]!);
                }
                catch  // lgtm[cs/catch-of-all-exceptions]
                {
                    // not a valid global.json file
                    continue;
                }

                installScript &= DownloadDotNetVersion(builder, installDir, version);
                validGlobalJson = true;
            }

            return validGlobalJson ? installScript : BuildScript.Failure;
        }

        /// <summary>
        /// Returns a script for downloading a specific .NET Core SDK version, if the
        /// version is not already installed.
        ///
        /// See https://docs.microsoft.com/en-us/dotnet/core/tools/dotnet-install-script.
        /// </summary>
        private static BuildScript DownloadDotNetVersion(IAutobuilder<AutobuildOptionsShared> builder, string path, string version)
        {
            return BuildScript.Bind(GetInstalledSdksScript(builder.Actions), (sdks, sdksRet) =>
                {
                    if (sdksRet == 0 && sdks.Count == 1 && sdks[0].StartsWith(version + " ", StringComparison.Ordinal))
                        // The requested SDK is already installed (and no other SDKs are installed), so
                        // no need to reinstall
                        return BuildScript.Failure;

                    builder.Log(Severity.Info, "Attempting to download .NET Core {0}", version);

                    if (builder.Actions.IsWindows())
                    {

                        var psCommand = $"[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; &([scriptblock]::Create((Invoke-WebRequest -UseBasicParsing 'https://dot.net/v1/dotnet-install.ps1'))) -Version {version} -InstallDir {path}";

                        BuildScript GetInstall(string pwsh) =>
                            new CommandBuilder(builder.Actions).
                            RunCommand(pwsh).
                            Argument("-NoProfile").
                            Argument("-ExecutionPolicy").
                            Argument("unrestricted").
                            Argument("-Command").
                            Argument("\"" + psCommand + "\"").
                            Script;

                        return GetInstall("pwsh") | GetInstall("powershell");
                    }
                    else
                    {
                        var dotnetInstallPath = builder.Actions.PathCombine(FileUtils.GetTemporaryWorkingDirectory(
                            builder.Actions.GetEnvironmentVariable,
                            builder.Options.Language.UpperCaseName,
                            out var shouldCleanUp), ".dotnet", "dotnet-install.sh");

                        var downloadDotNetInstallSh = BuildScript.DownloadFile(
                            "https://dot.net/v1/dotnet-install.sh",
                            dotnetInstallPath,
                            e => builder.Log(Severity.Warning, $"Failed to download 'dotnet-install.sh': {e.Message}"));

                        var chmod = new CommandBuilder(builder.Actions).
                            RunCommand("chmod").
                            Argument("u+x").
                            Argument(dotnetInstallPath);

                        var install = new CommandBuilder(builder.Actions).
                            RunCommand(dotnetInstallPath).
                            Argument("--channel").
                            Argument("release").
                            Argument("--version").
                            Argument(version).
                            Argument("--install-dir").
                            Argument(path);

                        var buildScript = downloadDotNetInstallSh & chmod.Script & install.Script;

                        if (shouldCleanUp)
                        {
                            var removeScript = new CommandBuilder(builder.Actions).
                                RunCommand("rm").
                                Argument(dotnetInstallPath);
                            buildScript &= removeScript.Script;
                        }

                        return buildScript;
                    }
                });
        }

        private static BuildScript GetInstalledSdksScript(IBuildActions actions)
        {
            var listSdks = new CommandBuilder(actions, silent: true).
                RunCommand("dotnet").
                Argument("--list-sdks");
            return listSdks.Script;
        }

        private static string DotNetCommand(IBuildActions actions, string? dotNetPath) =>
            dotNetPath is not null ? actions.PathCombine(dotNetPath, "dotnet") : "dotnet";

        private static BuildScript GetInfoCommand(IBuildActions actions, string? dotNetPath, IDictionary<string, string>? environment)
        {
            var info = new CommandBuilder(actions, null, environment).
                RunCommand(DotNetCommand(actions, dotNetPath)).
                Argument("--info");
            return info.Script;
        }

        private static CommandBuilder GetCleanCommand(IBuildActions actions, string? dotNetPath, IDictionary<string, string>? environment)
        {
            var clean = new CommandBuilder(actions, null, environment).
                RunCommand(DotNetCommand(actions, dotNetPath)).
                Argument("clean");
            return clean;
        }

        private static CommandBuilder GetRestoreCommand(IBuildActions actions, string? dotNetPath, IDictionary<string, string>? environment)
        {
            var restore = new CommandBuilder(actions, null, environment).
                RunCommand(DotNetCommand(actions, dotNetPath)).
                Argument("restore");
            return restore;
        }

        /// <summary>
        /// Gets the `dotnet build` script.
        /// </summary>
        private static BuildScript GetBuildScript(IAutobuilder<CSharpAutobuildOptions> builder, string? dotNetPath, IDictionary<string, string>? environment, string projOrSln)
        {
            var build = new CommandBuilder(builder.Actions, null, environment);
            var script = build.RunCommand(DotNetCommand(builder.Actions, dotNetPath)).
                Argument("build").
                Argument("--no-incremental");

            return
                script.Argument(builder.Options.DotNetArguments).
                    QuoteArgument(projOrSln).
                    Script;
        }
    }
}
