/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class JetBrainsProductCode(val code: String, val displayName: String? = null) {
    @SerialName("AC")
    AppCode("AC", "AppCode"),

    @SerialName("CC")
    CodeCanvas("CC"),

    @SerialName("CL")
    CLion("CL", "CLion"),

    @SerialName("CLN")
    CLionNova("CLN", "CLion Nova"),

    @SerialName("CWML")
    CodeWithMeLobby("CWML"),

    @SerialName("CWMR")
    CodeWithMeRelay("CWMR"),

    @SerialName("DC")
    dotCover("DC"),

    @SerialName("DCCLT")
    dotCoverCommandLineTools("DCCLT"),

    @SerialName("DG")
    DataGrip("DG", "DataGrip"),

    @SerialName("DL")
    Datalore("DL"),

    @SerialName("DLE")
    DataloreEnterprise("DLE"),

    @SerialName("DM")
    dotMemory("DM"),

    @SerialName("DMCLP")
    dotMemoryCommandLineProfiler("DMCLP"),

    @SerialName("DMU")
    dotMemoryUnit("DMU"),

    @SerialName("DP")
    dotTrace("DP"),

    @SerialName("DPCLT")
    dotTraceCommandLineTools("DPCLT"),

    @SerialName("DPK")
    dotPeek("DPK"),

    @SerialName("DPPS")
    dotTraceProfilingSDK("DPPS"),

    @SerialName("DS")
    DataSpell("DS", "DataSpell"),

    @SerialName("EHS")
    ETWHostService("EHS"),

    @SerialName("FL")
    Fleet("FL", "Fleet"),

    @SerialName("FLIJ")
    FleetBackend("FLIJ"),

    @SerialName("FLL")
    FleetLauncher("FLL"),

    @SerialName("FLS")
    FloatingLicenseServer("FLS"),

    @SerialName("GO")
    GoLand("GO", "GoLand"),

    @SerialName("GW")
    Gateway("GW", "JetBrains Gateway"),

    @SerialName("HB")
    Hub("HB"),

    @SerialName("HCC")
    HTTPClientCLI("HCC"),

    @SerialName("IDES")
    IDEServices("IDES"),

    @SerialName("IIC")
    IntelliJIDEACommunityEdition("IIC", "IntelliJ IDEA Community"),

    @SerialName("IIE")
    IntelliJIDEAEdu("IIE", "IntelliJ IDEA Educational"),

    @SerialName("IIU")
    IntelliJIDEAUltimate("IIU", "IntelliJ IDEA Ultimate"),

    @SerialName("JCD")
    JetBrainsClientsDownloader("JCD"),

    @SerialName("KT")
    Kotlin("KT", "Kotlin"),

    @SerialName("MF")
    MonoFont("MF"),

    @SerialName("MPS")
    MPS("MPS"),

    @SerialName("MPSIIP")
    MPSIntelliJIDEAplugin("MPSIIP"),

    @SerialName("PCC")
    PyCharmCommunityEdition("PCC", "PyCharm Community"),

    @SerialName("PCE")
    PyCharmEdu("PCE"),

    @SerialName("PCP")
    PyCharmProfessionalEdition("PCP", "PyCharm Professional"),

    @SerialName("PS")
    PhpStorm("PS", "PhpStorm"),

    @SerialName("QA")
    Aqua("QA"),

    @SerialName("QDANDC")
    QodanaCommunityforAndroid("QDANDC"),

    @SerialName("QDCLD")
    QodanaCloud("QDCLD"),

    @SerialName("QDGO")
    QodanaforGo("QDGO"),

    @SerialName("QDJS")
    QodanaforJS("QDJS"),

    @SerialName("QDJVM")
    QodanaforJVM("QDJVM"),

    @SerialName("QDJVMC")
    QodanaCommunityforJVM("QDJVMC"),

    @SerialName("QDNET")
    QodanaforNET("QDNET"),

    @SerialName("QDPHP")
    QodanaforPHP("QDPHP"),

    @SerialName("QDPY")
    QodanaforPython("QDPY"),

    @SerialName("QDPYC")
    QodanaCommunityforPython("QDPYC"),

    @SerialName("RC")
    ReSharperCPlusPlus("RC"),

    @SerialName("RD")
    Rider("RD"),

    @SerialName("RDCPPP")
    RiderforUnrealEngine("RDCPPP"),

    @SerialName("RFU")
    RiderFlowforUnity("RFU"),

    @SerialName("RM")
    RubyMine("RM"),

    @SerialName("RR")
    RustRover("RR"),

    @SerialName("RRD")
    RiderRemoteDebugger("RRD"),

    @SerialName("RS")
    ReSharper("RS"),

    @SerialName("RSCHB")
    ReSharperCheckedbuilds("RSCHB"),

    @SerialName("RSCLT")
    ReSharperCommandLineTools("RSCLT"),

    @SerialName("RSU")
    ReSharperTools("RSU"),

    @SerialName("SP")
    SpaceCloud("SP"),

    @SerialName("SPA")
    SpaceDesktop("SPA"),

    @SerialName("SPP")
    SpaceOnPremises("SPP"),

    @SerialName("TBA")
    ToolboxApp("TBA"),

    @SerialName("TC")
    TeamCity("TC"),

    @SerialName("TCC")
    TeamCityCloud("TCC"),

    @SerialName("US")
    Upsource("US"),

    @SerialName("WRS")
    Writerside("WRS"),

    @SerialName("WS")
    WebStorm("WS", "WebStorm"),

    @SerialName("YTD")
    YouTrack("YTD", "YouTrack"),

    @SerialName("YTWE")
    YoutrackWorkflowEditor("YTWE"),

    @SerialName("unknown")
    UnknownProduct("unknown");

    companion object {
        fun byProductCode(code: String): JetBrainsProductCode? {
            return entries.firstOrNull { it.code == code }
        }
    }
}