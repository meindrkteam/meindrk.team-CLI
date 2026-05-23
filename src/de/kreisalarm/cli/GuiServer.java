package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class GuiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper ();
    private static volatile long lastPing = System.currentTimeMillis ();

    private static final String SVG_DATA_URI =
        "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0idXRmLTgiPz4NCjwhLS0gR2VuZXJhdG9yOiAkJCQvR2VuZXJhbFN0ci8xOTY9QWRvYmUgSWxsdXN0cmF0b3IgMjcuNi4wLCBTVkcgRXhwb3J0IFBsdWctSW4gLiBTVkcgVmVyc2lvbjogNi4wMCBCdWlsZCAwKSAgLS0+DQo8c3ZnIHZlcnNpb249IjEuMSIgaWQ9IkxheWVyXzEiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgeG1sbnM6eGxpbms9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGxpbmsiIHg9IjBweCIgeT0iMHB4Ig0KCSB2aWV3Qm94PSIwIDAgMjIuNiAzNC44IiBzdHlsZT0iZW5hYmxlLWJhY2tncm91bmQ6bmV3IDAgMCAyMi42IDM0Ljg7IiB4bWw6c3BhY2U9InByZXNlcnZlIj4NCjxzdHlsZSB0eXBlPSJ0ZXh0L2NzcyI+DQoJLnN0MHtmaWxsOiNFMzBBMTU7fQ0KCS5zdDF7ZmlsbDojRTMwQzEzO30NCjwvc3R5bGU+DQo8cGF0aCBpZD0iWE1MSURfNjJfIiBkPSJNNSwxOS41di04YzAsMCwwLTAuNCwwLjItMC44SDEuOGMwLDAtMS42LDAtMS42LDEuNnY1LjZjMCwxLjYsMS42LDEuNiwxLjYsMS42djhoNC44di02LjQNCglDNi42LDIxLjEsNSwyMS4xLDUsMTkuNUw1LDE5LjV6IE01LDE5LjUiLz4NCjxwYXRoIGlkPSJYTUxJRF81OV8iIGQ9Ik01LDkuOWMwLjQsMCwwLjgtMC4xLDEuMS0wLjNjMCwwLDAuMS0wLjEsMC4yLTAuMWMwLjctMC40LDEuMS0xLjIsMS4xLTJjMC0xLjMtMS4xLTIuNC0yLjQtMi40DQoJYy0xLjMsMC0yLjQsMS4xLTIuNCwyLjRDMi42LDguOCwzLjYsOS45LDUsOS45TDUsOS45eiBNNSw5LjkiLz4NCjxwYXRoIGlkPSJYTUxJRF81Nl8iIGQ9Ik0yMSwxMC43aC0zLjRjMC4yLDAuNCwwLjIsMC44LDAuMiwwLjh2OGMwLDEuNi0xLjYsMS42LTEuNiwxLjZ2Ni40SDIxdi04YzAsMCwxLjYsMCwxLjYtMS42di01LjYNCglDMjIuNiwxMi4zLDIyLjYsMTAuNywyMSwxMC43TDIxLDEwLjd6IE0yMSwxMC43Ii8+DQo8cGF0aCBpZD0iWE1MSURfNTNfIiBkPSJNMTYuNSw5LjVjMC4xLDAsMC4xLDAuMSwwLjIsMC4xYzAuMywwLjIsMC43LDAuMywxLjEsMC4zYzEuMywwLDIuNC0xLjEsMi40LTIuNGMwLTEuMy0xLjEtMi40LTIuNC0yLjQNCgljLTEuMywwLTIuNCwxLjEtMi40LDIuNEMxNS40LDguMywxNS44LDksMTYuNSw5LjVMMTYuNSw5LjV6IE0xNi41LDkuNSIvPg0KPHBhdGggaWQ9IlhNTElEXzUwXyIgY2xhc3M9InN0MCIgZD0iTTEzLjgsNy41YzAsMS4zLTEuMSwyLjQtMi40LDIuNEMxMCw5LjksOSw4LjgsOSw3LjVjMC0xLjMsMS4xLTIuNCwyLjQtMi40DQoJQzEyLjcsNS4xLDEzLjgsNi4xLDEzLjgsNy41TDEzLjgsNy41eiBNMTMuOCw3LjUiLz4NCjxwYXRoIGlkPSJYTUxJRF80N18iIGNsYXNzPSJzdDAiIGQ9Ik0xNi4yLDEyLjNjMCwwLDAtMS42LTEuNi0xLjZIOC4yYzAsMC0xLjYsMC0xLjYsMS42djUuNmMwLDEuNiwxLjYsMS42LDEuNiwxLjZ2OGg2LjR2LTgNCgljMCwwLDEuNiwwLDEuNi0xLjZWMTIuM3ogTTE2LjIsMTIuMyIvPg0KPGcgaWQ9IlhNTElEXzM4XyI+DQoJPHBhdGggaWQ9IlhNTElEXzM5XyIgZD0iTTMuOSwyOUg2YzAuNSwwLDAuOCwwLDEuMSwwLjFjMC4zLDAuMSwwLjYsMC4zLDAuOSwwLjVzMC40LDAuNiwwLjUsMC45czAuMiwwLjgsMC4yLDEuNA0KCQljMCwwLjUtMC4xLDAuOS0wLjIsMS4yYy0wLjEsMC40LTAuMywwLjgtMC42LDFjLTAuMiwwLjItMC41LDAuMy0wLjgsMC41Yy0wLjMsMC4xLTAuNiwwLjEtMSwwLjFIMy45VjI5eiBNNS4xLDMwdjMuOGgwLjkNCgkJYzAuMywwLDAuNiwwLDAuNy0wLjFjMC4yLDAsMC4zLTAuMSwwLjUtMC4yczAuMi0wLjMsMC4zLTAuNnMwLjEtMC42LDAuMS0xczAtMC44LTAuMS0xcy0wLjItMC40LTAuMy0wLjZTNi44LDMwLjEsNi42LDMwDQoJCUM2LjQsMzAsNi4xLDMwLDUuNiwzMEg1LjF6Ii8+DQoJPHBhdGggaWQ9IlhNTElEXzQyXyIgZD0iTTkuNywzNC44VjI5aDIuNGMwLjYsMCwxLjEsMC4xLDEuMywwLjJzMC41LDAuMywwLjcsMC41czAuMiwwLjYsMC4yLDAuOWMwLDAuNC0wLjEsMC44LTAuNCwxLjENCgkJcy0wLjYsMC41LTEuMSwwLjVjMC4yLDAuMSwwLjUsMC4zLDAuNiwwLjVzMC40LDAuNSwwLjcsMC45bDAuNywxLjFoLTEuNGwtMC44LTEuMmMtMC4zLTAuNC0wLjUtMC43LTAuNi0wLjhzLTAuMi0wLjItMC4zLTAuMg0KCQlzLTAuMy0wLjEtMC42LTAuMWgtMC4ydjIuNEg5Ljd6IE0xMC45LDMxLjRoMC45YzAuNiwwLDAuOSwwLDEtMC4xczAuMi0wLjEsMC4zLTAuMnMwLjEtMC4zLDAuMS0wLjRjMC0wLjItMC4xLTAuMy0wLjItMC41DQoJCVMxMi44LDMwLDEyLjYsMzBjLTAuMSwwLTAuNCwwLTAuOCwwaC0wLjlWMzEuNHoiLz4NCgk8cGF0aCBpZD0iWE1MSURfNjVfIiBkPSJNMTUuNSwzNC44VjI5aDEuMnYyLjVMMTksMjloMS42bC0yLjIsMi4ybDIuMywzLjVoLTEuNWwtMS42LTIuN2wtMC45LDF2MS43SDE1LjV6Ii8+DQo8L2c+DQo8ZyBpZD0iWE1MSURfMjdfIj4NCgk8cGF0aCBpZD0iWE1MSURfMjhfIiBjbGFzcz0ic3QxIiBkPSJNNy4zLDMuN2MwLDAuMS0wLjEsMC4yLTAuMiwwLjJjLTAuMiwwLTAuMy0wLjEtMC4zLTAuNGMwLTAuNCwwLTAuNywwLjEtMQ0KCQljMC0wLjIsMC4xLTAuNCwwLjEtMC42YzAtMC4xLDAtMC4xLDAtMC4xUzcsMS43LDcuMSwxLjdjMC4yLDAsMC4zLDAuMSwwLjMsMC40YzAsMC4xLDAsMC4zLTAuMSwwLjZjMCwwLjItMC4xLDAuMy0wLjEsMC40DQoJCWMwLjQtMC44LDAuNy0xLjIsMS0xLjJjMC4xLDAsMC4yLDAsMC4zLDAuMXMwLjEsMC4zLDAuMSwwLjZjMCwwLjMsMCwwLjUsMCwwLjVjMCwwLDAuMS0wLjEsMC4yLTAuNEM5LjEsMi4zLDkuMiwyLjEsOS4zLDINCgkJczAuMi0wLjIsMC4zLTAuMkM5LjgsMTuuOSwxMCwyLjEsMTAsMnMwLjEsMC4zLDAuMSwwLjZjMCwwLjIsMCwwLjQsMC4xLDAuNXMwLjEsMC4yLDAuMiwwLjJjMC4xLDAsMC4xLDAuMSwwLjEsMC4xDQoJCWMwLDAuMSwwLDAuMS0wLjEsMC4ycy0wLjEsMC4xLTAuMiwwLjFjLTAuMSwwLTAuMi0wLjEtMC4zLTAuMlM5LjcsMy4yLDkuNywyLjhjMC0wLjIsMC0wLjMsMC0wLjRzMC0wLjEtMC4xLTAuMWMwLDAtMC4xLDAtMC4xLDAuMQ0KCQlTOS4zLDIuOCw5LjIsMy4xQzksMy4zLDguOSwzLjUsOC44LDMuNlM4LjcsMy43LDguNiwzLjdjLTAuMSwwLTAuMiwwLTAuMi0wLjFTOC4yLDMuMyw4LjIsMi45YzAtMC40LDAtMC41LTAuMS0wLjUNCgkJQzguMSwyLjQsOCwyLjUsNy44LDIuOVM3LjQsMy41LDcuMywzLjd6Ii8+DQoJPHBhdGggaWQ9IlhNTElEXzMwXyIgY2xhc3M9InN0MSIgZD0iTTEyLjUsMy4yYzAsMC4xLTAuMSwwLjMtMC4zLDAuNXMtMC41LDAuMy0wLjgsMC4zYy0wLjIsMC0wLjQtMC4xLTAuNS0wLjJzLTAuMi0wLjMtMC4yLTAuNg0KCQljMC0wLjQsMC4xLTAuNywwLjQtMXMwLjUtMC41LDAuOC0wLjVjMC4xLDAsMC4zLDAsMC40LDAuMXMwLjEsMC4yLDAuMSwwLjNjMCwwLjItMC4xLDAuNC0wLjMsMC42cy0wLjUsMC4zLTAuOCwwLjQNCgkJYzAsMC0wLjEsMC0wLjEsMC4xYzAsMC4xLDAsMC4xLDAuMSwwLjJzMC4xLDAuMSwwLjIsMC4xYzAuMywwLDAuNS0wLjEsMC44LTAuNGMwLTAuMSwwLjEtMC4xLDAuMS0wLjFDMTIuNSwzLDEyLjUsMy4xLDEyLjUsMy4yeg0KCQkgTTExLjIsMi44YzAuMiwwLDAuNC0wLjEsMC41LTAuM1MxMiwyLjMsMTIsMi4yYzAsMCwwLTAuMSwwLTAuMXMtMC4xLDAtMC4xLDBjLTAuMSwwLTAuMiwwLjEtMC40LDAuMlMxMS4zLDIuNiwxMS4yLDIuOHoiLz4NCgk8cGF0aCBpZD0iWE1MSURfMzNfIiBjbGFzcz0ic3QxIiBkPSJNMTMsMi42TDEzLDIuNkMxMywyLjMsMTMsMi4yLDEzLDIuMmMwLTAuMSwwLTAuMiwwLjEtMC4yczAuMS0wLjEsMC4yLTAuMWMwLjEsMCwwLjIsMCwwLjIsMC4xDQoJCVMxMy42LDIsMTMuNiwyczAsMC4yLTAuMSwwLjNsMCwwYy0wLjEsMC4zLTAuMSwwLjUtMC4xLDAuNnMwLDAuMywwLDAuNGMwLDAuMSwwLDAuMiwwLDAuM2MwLDAsMCwwLDAsMC4xYzAsMCwwLDAuMS0wLjEsMC4xDQoJCVMxMy4yLDQsMTMuMSw0Yy0wLjIsMC0wLjMtMC4xLTAuMy0wLjRjMC0wLjIsMCwwLjUsMC4xLTAuOEMxMywyLjcsMTMsMi42LDEzLDIuNnogTTEzLjksMC45YzAsMC4xLDAsMC4yLTAuMSwwLjJzLTAuMiwwLjEtMC4yLDAuMQ0KCQljLTAuMSwwLTAuMSwwLTAuMi0wLjFTMTMuMywxLjEsMTMuMywxYzAtMC4xLDAtMC4yLDAuMS0wLjJzMC4xLTAuMSwwLjMtMC4xYzAuMSwwLDAuMiwwLDAuMiwwLjFTMTMuOSwwLjksMTMuOSwwLjl6Ii8+DQoJPHBhdGggaWQ9IlhNTElEXzM2XyIgY2xhc3M9InN0MSIgZD0iTTE1LjYsMy42Yy0wLjEtMC4xLTAuMS0wLjMtMC4xLTAuN2MwLTAuMy0wLjEtMC41LTAuMi0wLjVjLTAuMSwwLTAuMywwLjItMC41LDAuNQ0KCQlzLTAuNCwwLjYtMC40LDAuOGMwLDAsMCwwLjEtMC4xLDAuMXMtMC4xLDAtMC4xLDBjLTAuMiwwLTAuMy0wLjEtMC4zLTAuM2MwLTAuMSwwLTAuMiwwLjEtMC40YzAuMS0wLjMsMC4xLTAuNiwwLjEtMQ0KCQljMC0wLjEsMC0wLjEsMC4xLTAuMnMwLjEtMC4xLDAuMi0wLjFjMC4xLDAsMC4xLDAsMC4yLDAuMXMwLjEsMC4xLDAuMSwwLjNjMCwwLjEsMCwwLjQtMC4xLDAuN2MwLjMtMC43LDAuNi0xLDAuOS0xDQoJCWMwLjMsMCwwLjUsMC4yLDAuNSwwLjdDMTYsMy4xLDE2LDMuNCwxNi4xLDMuNGMwLDAsMC4xLDAsMC4xLDBjMCwwLDAuMSwwLDAuMSwwYzAuMSwwLDAuMSwwLDAuMSwwLjFjMCwwLjEsMCwwLjEtMC4xLDAuMg0KCQlTMTYuMSwzLjgsMTYsMy44QzE1LjgsMy44LDE1LjcsMy43LDE1LjYsMy42TDE1LjYsMy42eiIvPg0KPC9nPg0KPC9zdmc+DQo=";

    private static final String HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="UTF-8"/>
<title>meinDRK CLI</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:sans-serif;background:#f0f2f5;min-height:100vh}
.lw{display:flex;justify-content:center;align-items:flex-start;padding:60px 20px}
.card{background:#fff;border-radius:10px;box-shadow:0 2px 12px rgba(0,0,0,.12);padding:28px;width:340px}
.logo{text-align:center;margin-bottom:20px}
.logo img{height:52px;max-width:200px;object-fit:contain}
label{display:block;font-size:11px;color:#6c757d;margin-bottom:3px;margin-top:10px}
input,select{width:100%;border:1px solid #ced4da;border-radius:5px;padding:6px 9px;font-size:13px}
input:focus,select:focus{outline:none;border-color:#cc0000;box-shadow:0 0 0 2px rgba(204,0,0,.15)}
.ub{background:#f8f9fa;border:1px solid #dee2e6;border-radius:5px;padding:6px 10px;display:flex;align-items:center;justify-content:space-between;cursor:pointer;margin-top:0}
.ub span{font-size:12px;color:#495057}
.ub a{font-size:11px;color:#0d6efd;text-decoration:none}
.ue{display:none;border:1px solid #0d6efd;border-radius:5px;padding:8px 10px;background:#f0f7ff;margin-top:6px}
.ue.open{display:block}
.ue input{border-color:#0d6efd}
.hint{font-size:10px;color:#6c757d;margin-top:3px}
.btn{width:100%;background:#cc0000;color:#fff;border:none;border-radius:5px;padding:9px;font-size:13px;font-weight:bold;cursor:pointer;margin-top:16px}
.btn:hover{background:#aa0000}
.btn-go{width:auto;margin-top:0;padding:6px 18px}
.alert{border-radius:5px;padding:10px 12px;margin-bottom:12px;font-size:12px}
.alert-danger{background:#f8d7da;border:1px solid #f5c2c7;color:#842029}
.alert-warning{background:#fff3cd;border:1px solid #ffc107;color:#856404}
.ci{font-size:24px;text-align:center;letter-spacing:10px;font-family:monospace;border-color:#ffc107}
.hidden{display:none!important}
.topbar{background:#cc0000;color:#fff;padding:8px 20px;display:flex;align-items:center;gap:12px}
.topbar img{height:28px;filter:brightness(0) invert(1)}
.sp{flex:1}
.kvb{font-size:11px;background:rgba(255,255,255,.2);border-radius:3px;padding:2px 8px}
.blo{font-size:11px;background:transparent;border:1px solid rgba(255,255,255,.5);color:#fff;border-radius:3px;padding:3px 10px;cursor:pointer}
.toolbar{background:#fff;border-bottom:1px solid #dee2e6;padding:12px 20px;display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap}
.toolbar label{margin-top:0}
.toolbar select,.toolbar input{width:auto}
.cs{font-weight:bold;color:#cc0000;border-color:#cc0000}
.ra{padding:16px 20px}
.rm{font-size:11px;color:#6c757d;margin-bottom:8px;font-family:monospace}
table{width:100%;border-collapse:collapse;font-size:12px;background:#fff;border-radius:6px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.08)}
th{background:#f8f9fa;border-bottom:2px solid #dee2e6;text-align:left;padding:8px 10px;font-size:11px;color:#495057;font-weight:600;white-space:nowrap}
td{border-bottom:1px solid #f0f0f0;padding:7px 10px}
tr:hover td{background:#fff8f8;cursor:pointer}
.badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:10px}
.bg{background:#d1e7dd;color:#0a3622}
.br{background:#e9ecef;color:#495057}
.kv td:first-child{font-weight:600;color:#495057;width:40%}
.spin{text-align:center;padding:30px;color:#6c757d;font-size:13px}
</style>
</head>
<body>

<div id="pl" class="lw">
  <div class="card">
    <div class="logo"><img src="SVG_DATA_URI_PLACEHOLDER" alt="meinDRK CLI"/></div>
    <div id="le" class="alert alert-danger hidden"></div>
    <div class="ub" onclick="togUrl()">
      <span>🌐 <strong id="ud">meindrk.team</strong></span>
      <a id="ul">&#228;ndern &#9662;</a>
    </div>
    <div class="ue" id="ue">
      <label style="margin-top:0">Server-URL</label>
      <input id="ui" type="url" value="https://meindrk.team" oninput="onUrl()"/>
      <div class="hint">Standard: https://meindrk.team</div>
    </div>
    <label>Kreisverband</label>
    <select id="ks"><option disabled selected>L&#228;dt&#8230;</option></select>
    <label>Benutzername</label>
    <input id="lu" type="text" autocomplete="username" placeholder="admin"/>
    <label>Passwort</label>
    <input id="lp" type="password" autocomplete="current-password" placeholder="&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;"/>
    <button class="btn" onclick="doLogin()">Anmelden</button>
  </div>
</div>

<div id="p2" class="lw hidden">
  <div class="card">
    <div class="logo"><img src="SVG_DATA_URI_PLACEHOLDER" alt="meinDRK CLI"/></div>
    <div id="ti" class="alert alert-warning">Bitte gib deinen Code ein.</div>
    <label style="text-align:center">6-stelliger Code</label>
    <input id="tc" class="ci" maxlength="6" placeholder="&#183;&#183;&#183;&#183;&#183;&#183;" autocomplete="one-time-code"/>
    <button class="btn" onclick="do2fa()">Best&#228;tigen</button>
    <button onclick="showLogin()" style="width:100%;background:transparent;border:none;color:#6c757d;font-size:11px;cursor:pointer;margin-top:8px;padding:4px">&#8592; Zur&#252;ck</button>
  </div>
</div>

<div id="pm" class="hidden">
  <div class="topbar">
    <img src="SVG_DATA_URI_PLACEHOLDER" alt="meinDRK"/>
    <div class="sp"></div>
    <span id="tu" style="font-size:12px;opacity:.9"></span>
    <span class="kvb" id="tk"></span>
    <button class="blo" onclick="doLogout()">Abmelden</button>
  </div>
  <div class="toolbar">
    <div><label>Befehl</label>
      <select class="cs" id="csel" onchange="updTb()">
        <option value="person list">person list</option>
        <option value="person get">person get</option>
        <option value="gruppe list">gruppe list</option>
        <option value="benutzer list">benutzer list</option>
        <option value="projekt list">projekt list</option>
      </select></div>
    <div id="tb-kv"><label>Kreisverband</label><select id="ck"></select></div>
    <div id="tb-q"><label>Suche (--q)</label><input id="cq" type="text" style="width:180px" placeholder="Name&#8230;"/></div>
    <div id="tb-li"><label>Limit</label><input id="cl" type="number" value="100" style="width:70px"/></div>
    <div id="tb-id"><label>Person-ID</label><input id="cpid" type="text" style="width:90px" placeholder="12345"/></div>
    <button class="btn btn-go" onclick="runCmd()">&#9654; Ausf&#252;hren</button>
  </div>
  <div class="ra">
    <div id="rm" class="rm"></div>
    <div id="rb"><p class="spin">Befehl w&#228;hlen und Ausf&#252;hren klicken.</p></div>
  </div>
</div>

<script>
var sUrl='https://meindrk.team',pendPw='',kvList=[];

function hb(){setInterval(function(){fetch('/api/ping').catch(function(){})},5000)}

window.onload=function(){
  hb();loadKvs(sUrl);
  fetch('/api/status').then(function(r){return r.json()}).then(function(s){
    if(s.loggedIn){showMain(s)}
    else{
      if(s.url){sUrl=s.url;setUD(s.url);document.getElementById('ui').value=s.url}
      if(s.login)document.getElementById('lu').value=s.login;
      showLogin()
    }
  }).catch(showLogin)
};

function togUrl(){
  var e=document.getElementById('ue'),l=document.getElementById('ul');
  l.textContent=e.classList.toggle('open')?'einklappen ▴':'&#228;ndern ▾'
}

function setUD(u){
  try{document.getElementById('ud').textContent=new URL(u).hostname}
  catch(x){document.getElementById('ud').textContent=u}
}

function onUrl(){var v=document.getElementById('ui').value.trim();sUrl=v;setUD(v);loadKvs(v)}

var kvTag=null;
function loadKvs(base){
  if(kvTag){document.head.removeChild(kvTag);kvTag=null}
  window.kreisverbaende=null;
  var s=document.getElementById('ks');
  s.innerHTML='<option disabled selected>L&#228;dt&#8230;</option>';
  base=base.replace(/[/]+$/,'');
  kvTag=document.createElement('script');
  kvTag.src=base+'/js/kreisverbaende.js?_='+Date.now();
  kvTag.onload=function(){if(window.kreisverbaende)fillKvs(window.kreisverbaende)};
  kvTag.onerror=function(){s.innerHTML='<option disabled selected>Nicht erreichbar</option>'};
  document.head.appendChild(kvTag)
}

function fillKvs(list){
  kvList=list.filter(function(k){return k.active});
  var g={};
  kvList.forEach(function(k){if(!g[k.organisation])g[k.organisation]=[];g[k.organisation].push(k)});
  var h='';
  Object.keys(g).sort().forEach(function(o){
    h+='<optgroup label="'+esc(o)+'">';
    g[o].sort(function(a,b){return a.name.localeCompare(b.name)}).forEach(function(k){
      h+='<option value="'+k.id+'">'+esc(k.name)+'</option>'
    });
    h+='</optgroup>'
  });
  document.getElementById('ks').innerHTML=h;fillCk()
}

function showLogin(){
  show('pl');hide('p2');hide('pm')
}

function showTfa(type){
  hide('pl');show('p2');
  document.getElementById('ti').textContent=
    type==='email'?'Ein Code wurde per E-Mail gesendet:':'Google Authenticator-Code:';
  document.getElementById('tc').value='';document.getElementById('tc').focus()
}

function showMain(s){
  hide('pl');hide('p2');show('pm');
  document.getElementById('tu').textContent=s.userName||s.login||'';
  document.getElementById('tk').textContent=kvById(s.kvid)||('KV '+s.kvid);
  fillCk(s.kvid)
}

function show(id){document.getElementById(id).classList.remove('hidden')}
function hide(id){document.getElementById(id).classList.add('hidden')}

function kvById(id){
  for(var i=0;i<kvList.length;i++)
    if(String(kvList[i].id)===String(id))return kvList[i].name;
  return null
}

function doLogin(){
  var pw=document.getElementById('lp').value;
  var u=document.getElementById('lu').value.trim();
  var url=document.getElementById('ui').value.trim()||sUrl;
  var kv=document.getElementById('ks').value;
  pendPw=pw;setLE('');
  post('/api/login',{url:url,login:u,password:pw,kvid:kv},function(r){
    if(r.ok){showMain(r.data)}
    else if(r.needs2fa){showTfa(r.tfaType)}
    else setLE(r.error||'Login fehlgeschlagen')
  },function(e){setLE('Verbindungsfehler: '+e.message)})
}

function do2fa(){
  var tok=document.getElementById('tc').value.trim();
  var u=document.getElementById('lu').value.trim();
  var url=document.getElementById('ui').value.trim()||sUrl;
  var kv=document.getElementById('ks').value;
  post('/api/login',{url:url,login:u,password:pendPw,token:tok,kvid:kv},function(r){
    if(r.ok){showMain(r.data)}
    else document.getElementById('ti').textContent=r.error||'Falscher Code'
  },function(e){alert('Fehler: '+e.message)})
}

function setLE(m){var e=document.getElementById('le');e.textContent=m;e.classList.toggle('hidden',!m)}

function doLogout(){post('/api/logout',{},function(){showLogin()},function(){})}

function fillCk(selId){
  var h='<option value="">Alle</option>';
  kvList.forEach(function(k){
    var s=selId&&String(k.id)===String(selId)?' selected':'';
    h+='<option value="'+k.id+'"'+s+'>'+esc(k.name)+'</option>'
  });
  document.getElementById('ck').innerHTML=h;
  if(selId)document.getElementById('ck').value=String(selId)
}

function updTb(){
  var cmd=document.getElementById('csel').value;
  sv('tb-q',cmd==='person list'||cmd==='gruppe list');
  sv('tb-li',cmd==='person list');
  sv('tb-id',cmd==='person get');
  sv('tb-kv',cmd!=='projekt list')
}

function sv(id,v){document.getElementById(id).style.display=v?'':'none'}

function runCmd(){
  var cmd=document.getElementById('csel').value;
  var kv=document.getElementById('ck').value;
  var q=document.getElementById('cq').value.trim();
  var lim=document.getElementById('cl').value;
  var id=document.getElementById('cpid').value.trim();
  document.getElementById('rb').innerHTML='<p class="spin">L&#228;dt&#8230;</p>';
  document.getElementById('rm').textContent='';
  post('/api/command',{cmd:cmd,kvid:kv,q:q,limit:lim,id:id},function(r){
    if(!r.ok){
      document.getElementById('rb').innerHTML='<div class="alert alert-danger" style="margin:16px">'+esc(r.error)+'</div>';
      return
    }
    var p=[cmd];
    if(kv)p.push('--kvid '+kv);if(q)p.push('--q '+q);
    if(lim&&cmd==='person list')p.push('--limit '+lim);if(id)p.push(id);
    document.getElementById('rm').textContent=(r.count||0)+' Ergebnis(se) · '+p.join(' ');
    document.getElementById('rb').innerHTML=renderRes(cmd,r.data)
  },function(e){
    document.getElementById('rb').innerHTML='<div class="alert alert-danger" style="margin:16px">Fehler: '+esc(e.message)+'</div>'
  })
}

var COLS={
  'person list':['id','projektID','nachname','vorname','geburtsdatum','status','aktiv'],
  'gruppe list':['id','projektID','name'],
  'benutzer list':['id','projektID','login','vorname','nachname','email','deaktiviert'],
  'projekt list':['id','name','organisation','prefix']
};

function renderRes(cmd,data){
  if(!data)return'<p class="spin">Keine Daten</p>';
  if(cmd==='person get')return renderKv(data);
  var cols=COLS[cmd]||[];
  if(!Array.isArray(data))data=[data];
  if(!data.length)return'<p class="spin">Keine Ergebnisse</p>';
  var h='<table><thead><tr>';
  cols.forEach(function(c){h+='<th>'+esc(c)+'</th>'});
  h+='</tr></thead><tbody>';
  data.forEach(function(row){
    var rid=row.id;
    h+='<tr'+(cmd==='person list'?' onclick="selP('+rid+')"':'')+' >';
    cols.forEach(function(c){
      var v=row[c]!=null?String(row[c]):'';
      if(c==='aktiv'||c==='deaktiviert'){
        var y=v==='Y'||v==='true';
        var ok=(c==='aktiv'&&y)||(c==='deaktiviert'&&!y);
        v='<span class="badge '+(ok?'bg':'br')+'">'+(y?'Ja':'Nein')+'</span>'
      }
      h+='<td>'+v+'</td>'
    });
    h+='</tr>'
  });
  h+='</tbody></table>';
  if(cmd==='person list')h+='<p style="font-size:11px;color:#adb5bd;text-align:right;margin-top:6px">Zeile anklicken → person get</p>';
  return h
}

function renderKv(data){
  if(!data||typeof data!=='object')return'<p class="spin">Keine Daten</p>';
  var h='<table class="kv"><tbody>';
  Object.keys(data).forEach(function(k){
    h+='<tr><td>'+esc(k)+'</td><td>'+esc(String(data[k]!=null?data[k]:''))+'</td></tr>'
  });
  return h+'</tbody></table>'
}

function selP(id){
  document.getElementById('csel').value='person get';
  document.getElementById('cpid').value=id;
  updTb();runCmd()
}

function post(url,body,ok,err){
  fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
  .then(function(r){return r.json()}).then(ok).catch(err)
}

function esc(s){
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
}
</script>
</body>
</html>
""";

    public static void start (Config config) throws Exception {
        HttpServer server = HttpServer.create (new InetSocketAddress ("127.0.0.1", 0), 0);
        int port = server.getAddress ().getPort ();
        String url = "http://localhost:" + port;

        server.createContext ("/",            ex -> handleRoot    (ex));
        server.createContext ("/api/ping",    ex -> handlePing    (ex));
        server.createContext ("/api/status",  ex -> handleStatus  (ex, config));
        server.createContext ("/api/login",   ex -> handleLogin   (ex, config));
        server.createContext ("/api/setup",   ex -> handleSetup   (ex, config));
        server.createContext ("/api/command", ex -> handleCommand (ex, config));
        server.createContext ("/api/logout",  ex -> handleLogout  (ex, config));
        server.start ();

        openBrowser (url);
        startHeartbeatMonitor ();
        Thread.currentThread ().join ();
    }

    private static void startHeartbeatMonitor () {
        Thread t = new Thread (() -> {
            while (true) {
                try { Thread.sleep (5_000); } catch (InterruptedException e) { return; }
                if (System.currentTimeMillis () - lastPing > 30_000) System.exit (0);
            }
        });
        t.setDaemon (true);
        t.start ();
    }

    private static void openBrowser (String url) {
        String os = System.getProperty ("os.name").toLowerCase ();
        try {
            ProcessBuilder pb;
            if (os.contains ("win"))      pb = new ProcessBuilder ("cmd", "/c", "start", url);
            else if (os.contains ("mac")) pb = new ProcessBuilder ("open", url);
            else                          pb = new ProcessBuilder ("xdg-open", url);
            pb.start ();
        } catch (IOException ignored) {}
    }

    private static void handleRoot (HttpExchange ex) throws IOException {
        if (!"GET".equals (ex.getRequestMethod ())) { ex.sendResponseHeaders (405, -1); return; }
        byte[] body = HTML_TEMPLATE.replace ("SVG_DATA_URI_PLACEHOLDER", SVG_DATA_URI)
                                   .getBytes (StandardCharsets.UTF_8);
        ex.getResponseHeaders ().set ("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders (200, body.length);
        try (var out = ex.getResponseBody ()) { out.write (body); }
    }

    private static void handlePing (HttpExchange ex) throws IOException {
        lastPing = System.currentTimeMillis ();
        sendJson (ex, "{\"ok\":true}");
    }

    private static void handleStatus (HttpExchange ex, Config config) throws IOException {
        if (!"GET".equals (ex.getRequestMethod ())) { ex.sendResponseHeaders (405, -1); return; }
        boolean loggedIn = false;
        if (config.getUrl () != null && config.getSession () != null) {
            try {
                new RestClient (config, false).getList ("Projekt", 1, null, null);
                loggedIn = true;
            } catch (Exception ignored) {}
        }
        ObjectNode n = MAPPER.createObjectNode ();
        n.put ("loggedIn", loggedIn);
        n.put ("url",   config.getUrl   () != null ? config.getUrl   () : "https://meindrk.team");
        n.put ("login", config.getLogin () != null ? config.getLogin () : "");
        n.put ("kvid",  config.getKvid  () != null ? config.getKvid  () : "");
        sendJson (ex, n.toString ());
    }

    private static void handleLogin (HttpExchange ex, Config config) throws IOException {
        if (!"POST".equals (ex.getRequestMethod ())) { ex.sendResponseHeaders (405, -1); return; }
        JsonNode req = MAPPER.readTree (ex.getRequestBody ());
        String url   = req.path ("url").asText ("https://meindrk.team");
        String login = req.path ("login").asText ("");
        String pw    = req.path ("password").asText ("");
        String token = req.path ("token").asText (null);
        String kvid  = req.path ("kvid").asText ("");
        if (token != null && token.isBlank ()) token = null;

        config.setUrl (url);
        config.setLogin (login);
        if (!kvid.isBlank ()) config.setKvid (kvid);
        if (config.getUuid () == null) config.setUuid (UUID.randomUUID ().toString ());
        try { config.save (); } catch (IOException ignored) {}

        try {
            RestClient client = new RestClient (config, false);
            JsonNode res = client.login (pw, token, config.getUuid ());
            String reason = res.path ("reason").asText ("");
            if (res.path ("success").asBoolean (false)) {
                JsonNode user = res.path ("user");
                ObjectNode data = MAPPER.createObjectNode ();
                data.put ("login",    login);
                data.put ("kvid",     kvid);
                data.put ("userName", user.path ("vorname").asText ("") + " "
                                    + user.path ("nachname").asText (""));
                ObjectNode env = MAPPER.createObjectNode ();
                env.put ("ok", true);
                env.set ("data", data);
                sendJson (ex, env.toString ());
            } else if (reason.contains ("google-authentification")) {
                sendJson (ex, "{\"ok\":false,\"needs2fa\":true,\"tfaType\":\"google\"}");
            } else if (reason.contains ("email-token") || reason.contains ("email code")) {
                sendJson (ex, "{\"ok\":false,\"needs2fa\":true,\"tfaType\":\"email\"}");
            } else {
                sendJson (ex, "{\"ok\":false,\"error\":\"" + jstr (reason.isBlank ()
                    ? "Login fehlgeschlagen" : reason) + "\"}");
            }
        } catch (Exception e) {
            sendJson (ex, "{\"ok\":false,\"error\":" + jstr (e.getMessage ()) + "}");
        }
    }

    private static void handleSetup (HttpExchange ex, Config config) throws IOException {
        if (!"POST".equals (ex.getRequestMethod ())) { ex.sendResponseHeaders (405, -1); return; }
        JsonNode req = MAPPER.readTree (ex.getRequestBody ());
        if (req.hasNonNull ("url"))   config.setUrl   (req.get ("url").asText ());
        if (req.hasNonNull ("login")) config.setLogin (req.get ("login").asText ());
        if (req.hasNonNull ("kvid"))  config.setKvid  (req.get ("kvid").asText ());
        try { config.save (); } catch (IOException ignored) {}
        sendJson (ex, "{\"ok\":true}");
    }

    private static void handleCommand (HttpExchange ex, Config config) throws IOException {
        if (!"POST".equals (ex.getRequestMethod ())) { ex.sendResponseHeaders (405, -1); return; }
        JsonNode req = MAPPER.readTree (ex.getRequestBody ());
        String cmd   = req.path ("cmd").asText ("");
        String kvid  = nvl (req.path ("kvid").asText (null));
        String q     = nvl (req.path ("q").asText (null));
        String limit = req.path ("limit").asText ("100");
        String id    = nvl (req.path ("id").asText (null));
        try {
            RestClient client = new RestClient (config, false);
            JsonNode data;
            int lim = 100;
            try { lim = Integer.parseInt (limit); } catch (NumberFormatException ignored) {}
            switch (cmd) {
                case "projekt list":
                    data = client.getList ("Projekt", 1000, null, null).path ("root"); break;
                case "person list":
                    data = client.getList ("Person", lim, q, kvid).path ("root"); break;
                case "person get":
                    if (id == null) { sendJson (ex, "{\"ok\":false,\"error\":\"ID fehlt\"}"); return; }
                    data = client.get ("/backend/rest/Person/" + id); break;
                case "gruppe list":
                    data = client.getList ("Gruppe", 1000, q, kvid).path ("root"); break;
                case "benutzer list":
                    data = client.getList ("Benutzer", 1000, null, kvid).path ("root"); break;
                default:
                    sendJson (ex, "{\"ok\":false,\"error\":\"Unbekannter Befehl\"}"); return;
            }
            ObjectNode env = MAPPER.createObjectNode ();
            env.put ("ok", true);
            env.set ("data", data);
            env.put ("count", data.isArray () ? data.size () : 1);
            sendJson (ex, env.toString ());
        } catch (Exception e) {
            sendJson (ex, "{\"ok\":false,\"error\":" + jstr (e.getMessage ()) + "}");
        }
    }

    private static void handleLogout (HttpExchange ex, Config config) throws IOException {
        config.setSession (null);
        try { config.save (); } catch (IOException ignored) {}
        sendJson (ex, "{\"ok\":true}");
    }

    private static void sendJson (HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes (StandardCharsets.UTF_8);
        ex.getResponseHeaders ().set ("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders ().set ("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders (200, body.length);
        try (var out = ex.getResponseBody ()) { out.write (body); }
    }

    private static String jstr (String s) {
        if (s == null) s = "";
        return s.replace ("\\", "\\\\").replace ("\"", "\\\"").replace ("\n", "\\n");
    }

    private static String nvl (String s) {
        return (s == null || s.isBlank ()) ? null : s;
    }
}
