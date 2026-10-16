package com.aicode.partyhub.core

import kotlinx.serialization.Serializable

@Serializable
data class GameInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val rules: String,
    val minPlayers: Int = 2,
    val maxPlayers: Int = 3,
    val realtime: Boolean = false,
    val bot: Boolean = true,
    val webBot: Boolean = bot
)

object GameCatalog {
    val entries = listOf(
        GameInfo("reaction", "反应抢按", "看准信号，再快一点", "arcade", "信号出现后按自己的按钮。提前抢按本轮不得分。共10轮，按反应时间排名计分；总分最高获胜。", realtime = true),
        GameInfo("color", "颜色陷阱", "相信颜色，不是文字", "arcade", "根据题目选出文字的实际颜色，不要被字义迷惑。每题每人只能回答一次，共12题；答对得分，答错扣分。", realtime = true),
        GameInfo("math", "心算抢答", "脑力也能来场竞速", "arcade", "从四个答案中选择正确结果。每题每人只答一次，共12题；答对得分，答错扣分。", realtime = true),
        GameInfo("memory", "记忆翻牌", "记住那张刚翻开的牌", "arcade", "轮流翻两张牌，相同则获得1分并继续操作，不同则短暂展示后轮到下一位。所有配对完成后分高者获胜。"),
        GameInfo("snake", "贪吃蛇竞技", "抢食，绕行，别撞上", "arcade", "方向按钮控制蛇前进，吃食物增长并得分；撞墙、自己或其他蛇会出局。最后存活者获胜，同时出局按得分比较。", realtime = true),
        GameInfo("tank", "坦克乱斗", "移动与瞄准的对决", "arcade", "方向键移动并转向，开火键射击；每人3点生命，子弹被墙阻挡，击中其他坦克扣血。最后存活者获胜。", realtime = true),
        GameInfo("bomber", "炸弹人", "为自己留一条退路", "arcade", "移动避开对手，放置炸弹后及时撤离。爆炸沿四方向传播，被墙阻挡，可炸毁木箱并引爆其他炸弹。最后存活者获胜。", realtime = true),
        GameInfo("dodge", "躲避竞技", "活到最后才算赢", "arcade", "各自移动躲避下落障碍，碰撞即出局；障碍随时间加快。最后存活者获胜，同一时刻出局可平局。", realtime = true),
        GameInfo("gomoku", "五子棋", "一线五子，先手布局", "board", "黑方先行，双方轮流在15×15棋盘落子。任意方向五连或长连获胜；本应用采用无禁手自由规则。", maxPlayers = 2, bot = true),
        GameInfo("xiangqi", "中国象棋", "楚河汉界，运筹帷幄", "board", "红先黑后。马走日、象走田、炮隔子吃子，将帅不得照面，不得送将。将死或困毙对手获胜；重复局面和无进展规则以局内提示为准。", maxPlayers = 2, bot = true),
        GameInfo("chess", "国际象棋", "走出属于你的妙手", "board", "白先黑后，以将死对方国王获胜。支持王车易位、吃过路兵与兵升变；无合法走法但未被将军为逼和，另按重复局面和无进展规则处理和棋。", maxPlayers = 2, bot = true),
        GameInfo("go", "围棋", "黑白之间，自有天地", "board", "黑先白后，支持9、13、19路。无气棋子被提，禁止自杀和重复局面；双方连续停一手后确认死子并按中国面积规则计分，白贴7.5目。", maxPlayers = 2, bot = true),
        GameInfo("reversi", "黑白棋", "角落里，藏着逆转", "board", "黑方先行，落子必须夹住至少一枚对手棋子，横竖斜线夹住的棋子全部翻转。无合法落子自动停一手，双方都不能落子时子多者获胜。", maxPlayers = 2, bot = true),
        GameInfo("chinese_checkers", "中国跳棋", "跳过阻碍，抢先到家", "board", "每人10枚棋子，目标是全部移入对面营地。可相邻一步或连续跳过紧邻棋子，跳跃不吃子；两人相对、三人间隔入座。", bot = true),
        GameInfo("draughts", "国际跳棋", "百格棋盘，连跳制胜", "board", "10×10棋盘，每人20子，白先。普通子向前走一步，可向前或后吃子；有吃必吃并选择吃子数最多的路线。王沿斜线远行，连吃结束才移除被吃子、判定升王。", maxPlayers = 2, bot = true),
        GameInfo("doudizhu", "斗地主", "一副牌，三人好时光", "cards", "54张牌、3个席位，缺人由AI补齐。轮流叫1至3分，最高分者拿3张底牌成为地主。支持顺子、连对、飞机、四带二、炸弹和王炸。地主先出完获胜，任一农民先出完则农民方胜；同机真人换手时遮屏。", minPlayers = 3, bot = true)
        ,GameInfo("tictactoe", "井字棋", "三连取胜，轻松开局", "board", "双方轮流在3×3棋盘落子，横、竖或斜线连成三子获胜，棋盘填满则平局。", maxPlayers = 2, bot = true)
        ,GameInfo("connect4", "四子棋", "落子连接四子", "board", "轮流选择列落子，棋子向下堆叠；横、竖或斜线连成四子获胜。", maxPlayers = 2, bot = true)
        ,GameInfo("coinflip", "硬币猜正反", "猜中就得分", "arcade", "每轮选择正面或反面，猜中得2分，五轮后分数最高者获胜。", realtime = true, bot = true)
        ,GameInfo("numberguess", "数字猜猜看", "离目标更近的人赢", "arcade", "每轮从四个数字中选择答案，答对得分，五轮后分数最高者获胜。", realtime = true, bot = true)
        ,GameInfo("oddone", "找出不同", "眼力比拼", "arcade", "四个选项中找出不同项，连续五轮累计得分。", realtime = true, bot = true)
        ,GameInfo("targettap", "目标点击", "锁定目标再出手", "arcade", "每轮选择目标位置，答对得分，五轮后分数最高者获胜。", realtime = true, bot = true)
        ,GameInfo("pattern", "图案记忆", "记住闪过的顺序", "arcade", "根据每轮图案提示选择答案，答对得分。", realtime = true, bot = true)
        ,GameInfo("sumten", "凑十挑战", "心算抢十", "arcade", "从四个答案中找出能凑成十的数字，累计五轮得分。", realtime = true, bot = true)
        ,GameInfo("highlow", "高低判断", "猜下一张更高还是更低", "arcade", "判断下一轮数值高低，连续五轮累计得分。", realtime = true, bot = true)
        ,GameInfo("color_match", "颜色配对", "颜色与位置的考验", "arcade", "选择正确颜色选项，答对得分。", realtime = true, bot = true)
        ,GameInfo("memoryrush", "极速记忆", "记住刚刚出现的牌", "arcade", "从四个选项中找出刚出现的图案，累计五轮得分。", realtime = true, bot = true)
        ,GameInfo("speedchoice", "极速四选一", "反应最快者胜", "arcade", "每轮快速选择正确选项，五轮后分数最高者获胜。", realtime = true, bot = true)
    )
    fun find(id: String): GameInfo = entries.first { it.id == id }
}
