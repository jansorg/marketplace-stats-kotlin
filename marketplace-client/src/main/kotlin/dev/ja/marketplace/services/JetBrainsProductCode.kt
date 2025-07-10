/*
 * Copyright (c) 2024-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
@Serializable(JetBrainsProductCodeSerializer::class)
sealed interface JetBrainsProductCode {
    val code: String

    companion object {
        private val codeMapping = ConcurrentHashMap<String, JetBrainsProductCode>()

        private fun register(code: String, displayName: String? = null): JetBrainsProductCode {
            return codeMapping.computeIfAbsent(code) { RegisteredCode(it, displayName) }
        }

        fun byProductCode(code: String): JetBrainsProductCode {
            return codeMapping[code] ?: codeMapping.computeIfAbsent(code) { UnknownCode(it) }
        }

        val Air = register("AIR")
        val AppCode = register("AC", "AppCode")
        val Aqua = register("QA")
        val AssistantVSCode = register("VSCAI", "JetBrains AI Assistant for Visual Studio Code")
        val CLion = register("CL", "CLion")
        val CLionNova = register("CLN", "CLion Nova")
        val CodeCanvas = register("CC")
        val CodeWithMeLobby = register("CWML")
        val CodeWithMeRelay = register("CWMR")
        val DataGrip = register("DG", "DataGrip")
        val DataSpell = register("DS", "DataSpell")
        val Datalore = register("DL")
        val DataloreEnterprise = register("DLE")
        val DevContainersCLI = register("IJDCT", "IJ Devcontainers CLI tool")
        val DotCover = register("DC")
        val DotCoverCommandLineTools = register("DCCLT")
        val DotMemory = register("DM")
        val DotMemoryCommandLineProfiler = register("DMCLP")
        val DotMemoryUnit = register("DMU")
        val DotPeek = register("DPK")
        val DotTrace = register("DP")
        val DotTraceCommandLineTools = register("DPCLT")
        val DotTraceProfilingSDK = register("DPPS")
        val ETWHostService = register("EHS")
        val Fleet = register("FL", "Fleet")
        val FleetBackend = register("FLIJ")
        val FleetLauncher = register("FLL")
        val FloatingLicenseServer = register("FLS")
        val Gateway = register("GW", "JetBrains Gateway")
        val GitClient = register("GIG", "GitClient")
        val GoLand = register("GO", "GoLand")
        val Grazie = register("GRZ")
        val HTTPClientCLI = register("HCC")
        val Hub = register("HB")
        val IDEServices = register("IDES")
        val IntelliJIDEACommunityEdition = register("IIC", "IntelliJ IDEA Community")
        val IntelliJIDEAEdu = register("IIE", "IntelliJ IDEA Educational")
        val IntelliJIDEAUltimate = register("IIU", "IntelliJ IDEA Ultimate")
        val JetBrainsClientsDownloader = register("JCD")
        val Kotlin = register("KT", "Kotlin")
        val MPS = register("MPS")
        val MPSIntelliJIDEAplugin = register("MPSIIP")
        val Mellum = register("MELLUM", "Mellum Enterprise")
        val MonoFont = register("MF")
        val PhpStorm = register("PS", "PhpStorm")
        val PyCharmCommunityEdition = register("PCC", "PyCharm Community")
        val PyCharmEdu = register("PCE")
        val PyCharmProfessionalEdition = register("PCP", "PyCharm Professional")
        val QodanaCloud = register("QDCLD")
        val QodanaCommunityforAndroid = register("QDANDC")
        val QodanaCommunityforJVM = register("QDJVMC")
        val QodanaCommunityforPython = register("QDPYC")
        val QodanaforGo = register("QDGO")
        val QodanaforJS = register("QDJS")
        val QodanaforJVM = register("QDJVM")
        val QodanaforNET = register("QDNET")
        val QodanaforPHP = register("QDPHP")
        val QodanaforPython = register("QDPY")
        val ReSharper = register("RS")
        val ReSharperCPlusPlus = register("RC")
        val ReSharperCheckedbuilds = register("RSCHB")
        val ReSharperCommandLineTools = register("RSCLT")
        val ReSharperTools = register("RSU")
        val ReSharperVSCode = register("VSCRS", "ReSharper for Visual Studio Code")
        val Rider = register("RD")
        val RiderFlowforUnity = register("RFU")
        val RiderRemoteDebugger = register("RRD")
        val RiderforUnrealEngine = register("RDCPPP")
        val RubyMine = register("RM")
        val RustRover = register("RR")
        val SpaceCloud = register("SP")
        val SpaceDesktop = register("SPA")
        val SpaceOnPremises = register("SPP")
        val TeamCity = register("TC")
        val TeamCityCloud = register("TCC")
        val ToolboxApp = register("TBA")
        val Upsource = register("US")
        val WebStorm = register("WS", "WebStorm")
        val Writerside = register("WRS")
        val YouTrack = register("YTD", "YouTrack")
        val YoutrackWorkflowEditor = register("YTWE")
    }

    data class RegisteredCode(override val code: String, val displayName: String? = null) : JetBrainsProductCode
    data class UnknownCode(override val code: String) : JetBrainsProductCode
}
