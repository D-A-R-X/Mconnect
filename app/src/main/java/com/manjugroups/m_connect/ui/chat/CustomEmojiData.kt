package com.manjugroups.m_connect.ui.chat

import android.content.Context

data class EmojiItem(val emoji: String, val keywords: String)

object CustomEmojiData {

    val categories = mapOf(
        "Smileys" to listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
            "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
            "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸",
            "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
            "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
            "🤔", "🫣", "🤭", "🥱", "🤫", "🤥", "😶", "😐", "😑", "😬",
            "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪",
            "😵", "😵‍💫", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕",
            "😈", "👿", "👹", "👺", "💀", "☠️", "👽", "👾", "🤖", "💩"
        ),
        "Gestures" to listOf(
            "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘",
            "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👍", "👎", "✊",
            "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️",
            "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃",
            "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄", "💋"
        ),
        "Hearts" to listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "❤️‍🔥",
            "❤️‍🩹", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝"
        ),
        "Food" to listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
            "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑",
            "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🥔", "🍠", "🥐",
            "🍞", "🥖", "🥨", "🥯", "🥞", "🧇", "🧀", "🍖", "🍗", "🥩",
            "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🥚", "🍳",
            "🥘", "🍲", "🍿", "🍣", "🍱", "🍦", "🍧", "🍩", "🍪", "🎂",
            "🍰", "🧁", "🥧", "🍫", "🍬", "🍭", "☕", "🍵", "🍺", "🍷"
        ),
        "Activities" to listOf(
            "🎉", "🥳", "🎈", "🎁", "🎂", "🎊", "🧧", "🏮", "🎇", "🎆",
            "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🎗️", "🎫", "🎟️", "⚽",
            "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🎱", "🪀", "🏓",
            "🏸", "🏒", "🥍", "🏹", "🎣", "🪁", "🎯", "🎮", "🕹️", "🎰",
            "🎲", "🧩", "🎳", "🛹", "⛸️", "🎿", "🏂", "🎬", "🎤", "🎧"
        ),
        "Travel" to listOf(
            "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
            "🛻", "🚚", "🚛", "🚜", "🛵", "🏍️", "🚲", "🛴", "🚏", "🚂",
            "🚄", "🚅", "🚇", "🚈", "🚞", "🚆", "🚢", "✈️", "🚀", "🛸",
            "🏠", "🏡", "🏢", "🏣", "🏥", "🏦", "🏨", "🏫", "🏪", "🏫",
            "🏖️", "⛰️", "🌋", "🏕️", "⛺", "🌅", "🌅", "🌆", "🌌", "🌍"
        ),
        "Symbols" to listOf(
            "🔥", "✨", "💯", "🌟", "⭐", "💥", "🌈", "⚡", "☀️", "❄️",
            "💧", "💤", "❌", "✅", "⚠️", "ℹ️", "➕", "➖", "❓", "❗️",
            "🔔", "💡", "🔑", "🔒", "🔓", "📢", "💬", "💭", "🎯", "🚩",
            "🏁", "🎵", "🎶", "🚫", "💤", "🔄", "🔁", "🔀", "🔇", "🔈"
        )
    )

    private val emojiList = mutableListOf<EmojiItem>()

    init {
        // Hydrate search keywords lookup
        addKeywords("😀😃😄😁😆😅😂🤣😊😇", "smile smiley happy face grin laugh joy grinning")
        addKeywords("🙂🙃😉😌", "smile soft happy wink relax wink upside down")
        addKeywords("😍🥰😘😗😙😚", "love heart eyes blow kiss smiling blush romance sweet romantic")
        addKeywords("😋😛😝😜🤪", "tongue crazy silly play delicious yum food tasty wink")
        addKeywords("🤨🧐🤓😎🥸", "think curious look glasses spectacles sunglasses cool smart spy")
        addKeywords("🥳😏😒😞😔😟😕🙁☹️", "party celebrate birthday sad sigh unhappy down depressed worry sorry")
        addKeywords("😣😖😫😩🥺", "cry weep begging please plead pain hurt struggle stress")
        addKeywords("😢😭😤😠😡🤬🤯", "cry weep tears sob sad angry mad furious red scream head explode shock surprise")
        addKeywords("😳🥵🥶😱😨😰😥😓🤗", "blush hot sweat cold freeze fear shock scary surprise hug open hands")
        addKeywords("🤔🫣🤭🥱🤫🤥😶😐😑😬🙄", "think hide eye giggle laugh whisper silence quiet yawn sleepy roll eyes nervous awkward")
        addKeywords("😯😦😧😮😲😴🤤😪😵😵‍💫🤐🥴🤢🤮🤧😷🤒🤕", "shock surprise sleep sleepy tired snore zzz dizzy sick throw up vomit mask ill hurt pain green")
        addKeywords("😈👿👹👺💀☠️👽👾🤖💩", "devil demon red evil skull dead bones death alien martian robot poop shit brown smile")
        
        addKeywords("👍👌✌️🤞🤝🙏👏🙌", "yes like good agree ok okay peace two fingers luck crossed deal greet handshake thank you thanks pray praise please wave hello bye clap applaud congratulations")
        addKeywords("👎🖕👊🤛🤜✊👈👉👆👇☝️✍️💅🤳💪", "no dislike bad punch fight flex strong arm power write note draw hand point finger nail polish selfie phone")
        addKeywords("👀👁️👄👅👂👃🧠💋", "eyes look watch see hear listen smell brain think head kiss lips red mouth tongue")
        
        addKeywords("❤️🧡💛💚💙💜🖤🤍🤎❤️‍🔥❤️‍🩹💔❣️💕💞💓💗💖💘💝", "love heart like red orange yellow green blue purple black white pink sparkle beat pulse grow arrow gift present romance")
        
        addKeywords("🍏🍎🍇🍓🍒🍉🍕🍔🍟🌭🥪🍕🌮🌯🥚🍳🍿🍣☕🍵🍺🍷", "apple fruit food eat drink hungry pizza cheese burger meat fries hotdog sandwich taco egg cook pop corn sushi coffee tea beer alcohol wine cup glass")
        
        addKeywords("🎉🥳🎈🎁🎂🏆🥇🏅🎫🎮🕹️🎲🎯🎬🎤🎧🎸", "party celebrate birthday balloon gift present win cup first place gold award ticket play game controller play station video game music mic sing song guitar instrument")
        
        addKeywords("🚗🚕🚙🚌🏎️🚓🚑🚒🚚🚜🏍️🚲🚇✈️🚀🛸🏠🏖️☀️❄️🌍🗺️📅⏰", "car auto drive vehicle police ambulance fire bike cycle flight plane space rocket house home beach summer hot weather cold winter snow earth world map calendar date schedule time alarm clock")
        
        addKeywords("🔥✨💯🌟⭐💥🌈⚡💤❌✅⚠️ℹ️❓❗️🔔💡🔑🔒💬", "fire hot burn flame trend sparkles shine star gold score hundred perfect explosion blast flash storm lightning rain sleeping cross delete done correct sign warn caution info question mark help exclamation call message speak lock key light bulb idea")
    }

    private fun addKeywords(emojis: String, keywords: String) {
        // Handle multi-character emojis (surrogates) correctly
        var i = 0
        while (i < emojis.length) {
            val codePoint = emojis.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val emojiStr = emojis.substring(i, i + charCount)
            emojiList.add(EmojiItem(emojiStr, keywords))
            i += charCount
        }
    }

    fun searchEmojis(query: String): List<String> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isBlank()) return emptyList()
        return emojiList.filter { item ->
            item.keywords.contains(trimmed) || item.emoji == trimmed
        }.map { it.emoji }.distinct()
    }

    // Persistent favorites caching helpers
    private const val PREFS_FAVORITES = "emoji_favorites"
    private const val KEY_FAVS = "favorite_emojis_list"
    private const val MAX_FAVORITES = 35

    private fun isValidEmoji(emoji: String): Boolean {
        if (emoji.isBlank()) return false
        val trimmed = emoji.trim()
        if (trimmed.isEmpty()) return false
        // Ensure there is at least one non-whitespace, non-control, non-formatting-only character.
        return trimmed.any { char ->
            val code = char.code
            val type = Character.getType(char)
            !Character.isWhitespace(char) &&
                    code != 0 &&
                    code != 0x200B && // Zero Width Space
                    char != '\uFEFF' && // BOM
                    char != '\u200B' &&
                    char != '\u200C' &&
                    char != '\u200D' && // Keep ZWJ if accompanied by visible emoji parts
                    type != Character.CONTROL.toInt() &&
                    type != Character.FORMAT.toInt()
        }
    }

    fun getFavorites(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FAVS, null) ?: return defaultFavorites()
        return raw.split(",")
            .map { it.trim() }
            .filter { isValidEmoji(it) }
    }

    fun addFavorite(context: Context, emoji: String) {
        val trimmedEmoji = emoji.trim()
        if (!isValidEmoji(trimmedEmoji)) return
        val current = getFavorites(context).toMutableList()
        current.remove(trimmedEmoji)
        current.add(0, trimmedEmoji) // Bring to front
        val trimmed = if (current.size > MAX_FAVORITES) current.take(MAX_FAVORITES) else current
        val prefs = context.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FAVS, trimmed.joinToString(",")).apply()
    }

    private fun defaultFavorites(): List<String> {
        return listOf(
            "❤️", "👍", "😂", "🔥", "✨", "😊", "😍", "🎉", "🙏", "👏",
            "🙌", "🤣", "🥰", "😘", "😜", "🤔", "😭", "😢", "😎", "💯",
            "🤩", "🤷", "🤦", "👀", "👌", "💪", "💡", "✔️", "❌", "⚠️"
        )
    }
}
