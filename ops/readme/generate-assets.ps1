# Regenerate the three README illustrations on Windows. Glyph outlines keep GitHub rendering independent of installed fonts.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase
$repo=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out=Join-Path $repo 'assets/readme'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ink='#2c302f'; $red='#f56c72'; $teal='#27aaa5'; $yellow='#ffe784'
function Start-Paper($height,$title,$description) {
    $script:parts=[System.Collections.Generic.List[string]]::new()
    $parts.Add('<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="'+$height+'" viewBox="0 0 1440 '+$height+'" role="img" aria-labelledby="title desc"><title id="title">'+$title+'</title><desc id="desc">'+$description+'</desc>')
    $parts.Add('<defs><pattern id="rule" width="1440" height="36" patternUnits="userSpaceOnUse"><path d="M0 35.5H1440" stroke="#dce7e6" stroke-width="1"/></pattern></defs>')
    $parts.Add('<rect width="1440" height="'+$height+'" fill="#faf9f1"/><rect width="1440" height="'+$height+'" fill="url(#rule)"/><path d="M100 12V'+($height-12)+'" stroke="#f0c4be"/>')
}
function Text($value,$x,$y,$size=25,$family='KaiTi',$color=$ink) {
    $f=[System.Windows.Media.FormattedText]::new($value,[System.Globalization.CultureInfo]::InvariantCulture,[System.Windows.FlowDirection]::LeftToRight,[System.Windows.Media.Typeface]::new($family),$size,[System.Windows.Media.Brushes]::Black,1)
    $g=[System.Windows.Media.PathGeometry]::CreateFromGeometry($f.BuildGeometry([System.Windows.Point]::new(0,0)))
    $d=$g.ToString([System.Globalization.CultureInfo]::InvariantCulture) -replace '^F[01]',''
    $d=[regex]::Replace($d,'-?\d+\.\d+',{param($m) [Math]::Round([double]::Parse($m.Value,[System.Globalization.CultureInfo]::InvariantCulture),3).ToString([System.Globalization.CultureInfo]::InvariantCulture)})
    $parts.Add('<g transform="translate('+$x+' '+$y+')" aria-label="'+[System.Security.SecurityElement]::Escape($value)+'"><path d="'+$d+'" fill="'+$color+'" fill-rule="nonzero"/></g>')
}
function Box($x,$y,$w,$h,$fill='#fffef7',$stroke=$ink) {
    $parts.Add('<path d="M'+($x+16)+' '+$y+' Q'+($x+2)+' '+($y-1)+' '+$x+' '+($y+15)+' L'+($x+1)+' '+($y+$h-16)+' Q'+($x-1)+' '+($y+$h+1)+' '+($x+18)+' '+($y+$h)+' L'+($x+$w-16)+' '+($y+$h-1)+' Q'+($x+$w+2)+' '+($y+$h+1)+' '+($x+$w)+' '+($y+$h-17)+' L'+($x+$w-1)+' '+($y+16)+' Q'+($x+$w+1)+' '+($y-1)+' '+($x+$w-18)+' '+($y+1)+' Z" fill="'+$fill+'" stroke="'+$stroke+'" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>')
    $parts.Add('<path d="M'+($x+15)+' '+($y+3)+' L'+($x+$w-26)+' '+($y+3)+' M'+($x+4)+' '+($y+22)+' L'+($x+3)+' '+($y+$h-20)+'" fill="none" stroke="'+$stroke+'" opacity=".24" stroke-width="1.1"/>')
}
function Highlight($x,$y,$w,$h) {$parts.Add('<path d="M'+$x+' '+($y+6)+' L'+($x+$w-3)+' '+$y+' L'+($x+$w)+' '+($y+$h-4)+' L'+($x+3)+' '+($y+$h)+'Z" fill="'+$yellow+'" opacity=".86"/>')}
function Underline($x,$y,$w) {
    $d='M'+$x+' '+$y
    for($i=0;$i -lt $w;$i+=36){$d+=' q9 8 18 0 t18 0'}
    $parts.Add('<path d="'+$d+'" fill="none" stroke="'+$red+'" stroke-width="3.1" stroke-linecap="round"/>')
}
function Pill($label,$x,$y,$w,$color=$ink) {Box $x $y $w 42 '#fffef7' $color; Text $label ($x+16) ($y+7) 21 'Segoe Print' $color}
function Save-Paper($name) {$parts.Add('</svg>');[System.IO.File]::WriteAllText((Join-Path $out $name),($parts -join "`n")+"`n",[System.Text.UTF8Encoding]::new($false))}

Start-Paper 430 'BoboWorld4J — 让对话、知识与工具协作' 'Java 与 Spring AI 应用平台。流式对话、带引用的文档问答、持久化 Agent、图片创作与真实用量结算。'
foreach($y in @(52,124,196,268,340)) {$parts.Add('<circle cx="50" cy="'+$y+'" r="9" fill="#fffef8" stroke="#b7bbb0" stroke-width="1.5"/><circle cx="50" cy="'+$y+'" r="2.5" fill="#d7d9d0"/>')}
Text 'Java + Spring AI / Agent 应用平台' 163 53 22 'KaiTi' '#69736e'
Highlight 157 105 676 91
Text 'BoboWorld4J' 163 94 76 'Segoe Print'
Underline 170 202 622
Text '让对话、知识与工具，在同一条任务链中协作。' 165 230 29
Text '从一句问题，到可追溯的答案。' 165 273 29
Pill 'Java 21' 165 344 137 $red
Pill 'Spring AI' 319 344 166 $teal
Pill 'RAG' 502 344 100 '#b08c12'
Pill 'Durable Agent' 619 344 223 $ink
Highlight 1014 49 94 25
Box 973 72 402 285 '#fffef7'
$parts.Add('<path d="M982 118H1366" stroke="#7f857e" stroke-width="1.5"/>')
foreach($c in @(@(997,$red),@(1019,$yellow),@(1041,'#6bd0c4'))) {$parts.Add('<circle cx="'+$c[0]+'" cy="96" r="6.5" fill="'+$c[1]+'" stroke="'+$ink+'" stroke-width="1.2"/>')}
Text 'bobo / task receipt' 1070 84 15 'Segoe Print' '#899087'
Text '$ analyze resume' 995 143 22 'Segoe Print'
Text '✓ 检索相关文档' 996 199 26 'KaiTi' $teal
Text '✓ 检查引用与事实' 996 242 26 'KaiTi' $teal
Text '✓ 保存答案和回执' 996 285 26 'KaiTi' $teal
Text 'github.com/bbb-520/Bobo4j' 1030 386 15 'Segoe Print' '#8f968c'
Save-Paper 'hero.svg'

Start-Paper 520 '一个任务是怎么走完的' '发起任务、模型决策、受控调用、结果校验和持久回执。证据不足时有限修复或明确拒答；全程保留事件、预算和调用回执。'
Highlight 65 28 466 57
Text '一个任务是怎么走完的' 78 34 42
Underline 80 92 432
Text '每一步有状态，每一次调用有回执。' 79 112 25 'KaiTi' '#69736e'
$cards=@(
 @('发起任务','问题 + 文档 / 图片','保存任务与请求号','#ffd3cc'),
 @('模型决策','检索证据，选择工具','检查身份与参数','#bceae3'),
 @('受控调用','白名单 + 轮次预算','持久化步骤与计费','#ffe999'),
 @('结果校验','检查引用与事实支持','不足时修复或拒答','#ffd3cc'),
 @('持久回执','答案、来源与事件','断线后继续查看','#bceae3'))
for($i=0;$i -lt 5;$i++) {
 $x=48+$i*281; $card=$cards[$i]
 Highlight ($x+74) 171 75 18
 Box $x 184 234 167
 $parts.Add('<circle cx="'+($x+30)+'" cy="215" r="17" fill="'+$card[3]+'" stroke="'+$ink+'" stroke-width="1.6"/>')
 Text ([string]($i+1)) ($x+23) 198 22 'Segoe Print'
 Text $card[0] ($x+60) 199 29
 Text $card[1] ($x+17) 259 22
 Text $card[2] ($x+17) 298 22
 if($i -lt 4){$parts.Add('<path d="M'+($x+245)+' 265 q12 -5 24 2 m-9 -9 10 9 -11 6" fill="none" stroke="'+$ink+'" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"/>')}
}
$parts.Add('<path d="M1005 358v25q0 18 -21 18H740q-21 0 -21 -18v-9m-7 9 7 -10 7 10" fill="none" stroke="'+$red+'" stroke-width="2" stroke-dasharray="7 6" stroke-linecap="round"/>')
Text '未通过：有限修复 / 明确拒答' 730 414 22 'KaiTi' $red
Box 175 465 300 37; Text '● 用户与租户隔离' 195 470 22 'KaiTi' $teal
Box 545 465 300 37; Text '● 停止、恢复与事件回放' 565 470 22
Box 915 465 350 37; Text '● 结果不明，保留核对状态' 935 470 22 'KaiTi' '#a58b16'
Save-Paper 'task-flow.svg'

Start-Paper 800 'BoboWorld4J 架构总览' '浏览器前端经网关访问持久化编排与聊天、文档、图片、内容领域服务。账户服务负责身份和计费；共享运行时、协议与签名能力连接模型供应商和基础设施。'
Highlight 65 29 547 58
Text 'BoboWorld4J 架构总览' 78 30 43
Underline 81 96 503
Text '边界清楚，任务与回执落在服务端。' 78 117 25 'KaiTi' '#69736e'
$layers=@(
 @{y=173;label='访问与身份';sub='Access';color='#ffe0d8';items=@(@('网页前端','Bobo_web'),@('统一网关','agent-gateway'),@('账户 / 权限 / 计费','agent-auth-service'))},
 @{y=310;label='任务编排';sub='Orchestration';color='#d8f1ec';items=@(@('持久执行','agent-orchestrator-service'),@('工具白名单','参数校验 / 调用账本'),@('预算与恢复','停止 / 续跑 / 事件回放'))},
 @{y=447;label='领域服务';sub='Domain services';color='#fff0b1';items=@(@('聊天与文档','agent-chat / agent-rag'),@('图片与内容','agent-media / agent-content'),@('答案与创作','引用校验 / 图片任务'))},
 @{y=584;label='公共能力';sub='Shared runtime';color='#eeede5';items=@(@('模型与存储客户端','agent-runtime'),@('跨服务协议','agent-api'),@('身份签名与公共配置','agent-common'))})
foreach($l in $layers) {
 $y=$l.y; Box 61 $y 1320 111 '#fffef8'; Box 83 ($y+16) 205 79 $l.color
 Text $l.label 109 ($y+26) 30; Text $l.sub 105 ($y+68) 15 'Segoe Print' '#7f877c'
 for($j=0;$j -lt 3;$j++) {$x=324+$j*345; Box $x ($y+21) 320 69; Text $l.items[$j][0] ($x+17) ($y+27) 26; Text $l.items[$j][1] ($x+17) ($y+63) 18 'KaiTi' '#65746f'}
 if($y -lt 584){$parts.Add('<path d="M720 '+($y+116)+'v13m-12 -9 12 10 12 -10" fill="none" stroke="'+$ink+'" stroke-width="2.2" stroke-linecap="round"/>')}
}
Highlight 63 726 1318 52
Text 'MySQL / Redis' 100 738 24 'Segoe Print'
Text 'Milvus / Nacos' 490 738 24 'Segoe Print'
Text '模型供应商 / OSS / 邮箱 / 支付' 889 739 25
Save-Paper 'architecture.svg'
Get-ChildItem -LiteralPath $out | Select-Object Name,Length
