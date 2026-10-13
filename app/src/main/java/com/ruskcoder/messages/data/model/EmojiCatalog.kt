package com.ruskcoder.messages.data.model

/** One emoji, and the words that should find it. */
data class Emoji(val glyph: String, val keywords: String)

/** A named run of them, as the picker groups its list. */
data class EmojiGroup(val label: String, val emoji: List<Emoji>)

/**
 * Each group written as one string of `glyph keywords` pairs, split on whitespace.
 *
 * A table of a few hundred `Emoji(...)` constructions says nothing a pair of columns does not, and
 * this stays readable at a glance — which matters, because the only maintenance this file will ever
 * see is somebody adding a glyph to a line.
 *
 * Keywords are dash-joined so the split can stay on whitespace; the search strips the dashes back
 * out, so typing "thumbs up" finds `thumbs-up`.
 */
private fun group(label: String, entries: String) = EmojiGroup(
    label,
    entries.trim().split(Regex("\\s+")).chunked(2).mapNotNull { pair ->
        if (pair.size == 2) Emoji(pair[0], pair[1]) else null
    },
)

val EMOJI_GROUPS: List<EmojiGroup> = listOf(
    group(
        "Smileys & people",
        """
        😀 grin 😃 smile 😄 happy 😁 beam 😆 laugh 😅 sweat-laugh 🤣 rofl 😂 tears-joy
        🙂 slight-smile 🙃 upside-down 😉 wink 😊 blush 😇 halo-innocent 🥰 love-hearts 😍 heart-eyes 🤩 star-struck
        😘 kiss 😗 kissing 😚 kissing-closed 😋 yum 😛 tongue 🤪 zany 😜 wink-tongue 🤨 raised-brow
        🧐 monocle 🤓 nerd 😎 sunglasses-cool 🥳 party-face 😏 smirk 😒 unamused 😞 disappointed 😔 pensive
        😟 worried 🙁 frown 😣 persevere 😖 confounded 😫 tired 😩 weary 🥺 pleading 😢 cry
        😭 sob 😤 triumph-huff 😠 angry 😡 rage 🤬 cursing 🤯 mind-blown 😳 flushed 🥵 hot
        🥶 cold 😱 scream 😨 fearful 😰 anxious 😥 sad-relieved 😓 downcast 🤗 hug 🤔 thinking
        🤭 hand-mouth 🤫 shush 🤥 lying 😶 no-mouth 😐 neutral 😑 expressionless 😬 grimace 🙄 eye-roll
        😴 sleep 🤤 drool 🤒 sick 🤕 injured 🤢 nauseated 🤮 vomit 🥴 woozy 😵 dizzy
        🤠 cowboy 🥸 disguise 🤡 clown 👻 ghost 💀 skull 👽 alien 🤖 robot 💩 poop
        """,
    ),
    group(
        "Gestures & body",
        """
        👍 thumbs-up 👎 thumbs-down 👌 ok-hand 🤌 pinched 🤏 pinch ✌️ victory-peace 🤞 crossed-fingers 🫰 fingers-crossed-money
        🤟 love-you 🤘 rock-horns 🤙 call-me 👈 point-left 👉 point-right 👆 point-up 👇 point-down ☝️ index-up
        ✋ raised-hand 🤚 back-hand 🖐️ splayed 🖖 vulcan 👋 wave 🤝 handshake 🙏 pray-thanks ✍️ writing
        💅 nail-care 🤳 selfie 💪 muscle-strong 🦾 mechanical-arm 🦿 mechanical-leg 🦵 leg 🦶 foot 👂 ear
        👃 nose 🧠 brain 🫀 heart-organ 🦷 tooth 👀 eyes 👁️ eye 👅 tongue-body 👄 mouth
        👏 clap 🙌 raised-hands 👐 open-hands 🤲 palms-up 🫶 heart-hands 🙋 raising-hand 🤷 shrug 🤦 facepalm
        """,
    ),
    group(
        "Animals & nature",
        """
        🐶 dog 🐱 cat 🐭 mouse 🐹 hamster 🐰 rabbit 🦊 fox 🐻 bear 🐼 panda
        🐨 koala 🐯 tiger 🦁 lion 🐮 cow 🐷 pig 🐸 frog 🐵 monkey 🙈 see-no-evil
        🙉 hear-no-evil 🙊 speak-no-evil 🐔 chicken 🐧 penguin 🐦 bird 🦆 duck 🦅 eagle 🦉 owl
        🦇 bat 🐺 wolf 🐗 boar 🐴 horse 🦄 unicorn 🐝 bee 🐛 caterpillar 🦋 butterfly
        🐌 snail 🐞 ladybug 🐢 turtle 🐍 snake 🐙 octopus 🦑 squid 🦐 shrimp 🦀 crab
        🐬 dolphin 🐳 whale 🐟 fish 🦈 shark 🐊 crocodile 🐘 elephant 🦒 giraffe 🦓 zebra
        🌵 cactus 🌲 evergreen 🌳 tree 🌴 palm 🌱 seedling 🌿 herb 🍀 clover-lucky 🍁 maple
        🍂 fallen-leaves 🍃 leaf-wind 🌸 blossom 🌺 hibiscus 🌻 sunflower 🌹 rose 🌷 tulip 💐 bouquet
        """,
    ),
    group(
        "Food & drink",
        """
        🍎 apple 🍐 pear 🍊 orange 🍋 lemon 🍌 banana 🍉 watermelon 🍇 grapes 🍓 strawberry
        🫐 blueberries 🍒 cherries 🍑 peach 🥭 mango 🍍 pineapple 🥥 coconut 🥝 kiwi 🍅 tomato
        🥑 avocado 🍆 eggplant 🥕 carrot 🌽 corn 🌶️ chilli 🥦 broccoli 🧄 garlic 🧅 onion
        🍞 bread 🥐 croissant 🥖 baguette 🧀 cheese 🥚 egg 🍳 fried-egg 🥞 pancakes 🧇 waffle
        🥓 bacon 🍔 burger 🍟 fries 🍕 pizza 🌭 hotdog 🥪 sandwich 🌮 taco 🌯 burrito
        🥗 salad 🍝 pasta 🍜 ramen 🍣 sushi 🍤 tempura 🍚 rice 🍦 ice-cream 🍩 doughnut
        🍪 cookie 🎂 birthday-cake 🍰 cake 🧁 cupcake 🍫 chocolate 🍬 candy 🍿 popcorn ☕ coffee
        🍵 tea 🧋 bubble-tea 🥤 soda 🍺 beer 🍻 cheers 🍷 wine 🍸 cocktail 🥂 champagne
        """,
    ),
    group(
        "Activity & sport",
        """
        ⚽ football-soccer 🏀 basketball 🏈 american-football ⚾ baseball 🎾 tennis 🏐 volleyball 🏉 rugby 🎱 pool-8ball
        🏓 ping-pong 🏸 badminton 🥅 goal 🏒 hockey 🏑 field-hockey 🥍 lacrosse 🏏 cricket 🥊 boxing
        🥋 martial-arts 🎽 running-shirt ⛸️ skating 🎿 ski 🛹 skateboard 🏋️ weightlifting 🤸 cartwheel 🤺 fencing
        ⛳ golf 🏹 archery 🎣 fishing 🤿 diving 🏊 swim 🚴 cycling 🧗 climbing 🏆 trophy
        🥇 gold-medal 🥈 silver-medal 🥉 bronze-medal 🎖️ medal 🎯 target-bullseye 🎮 gaming 🎲 dice 🎸 guitar
        🎹 piano 🎺 trumpet 🎷 saxophone 🥁 drum 🎤 microphone 🎧 headphones 🎬 movie 🎨 art
        """,
    ),
    group(
        "Travel & places",
        """
        🚗 car 🚕 taxi 🚙 suv 🚌 bus 🚎 trolley 🏎️ racing-car 🚓 police-car 🚑 ambulance
        🚒 fire-engine 🚚 truck 🚛 lorry 🚜 tractor 🛵 scooter 🏍️ motorcycle 🚲 bicycle 🛴 kick-scooter
        ✈️ plane 🛫 takeoff 🛬 landing 🚀 rocket 🛸 ufo 🚁 helicopter ⛵ sailboat 🚢 ship
        🚂 train 🚆 railway 🚇 metro 🚉 station 🗺️ map 🧭 compass 🏔️ mountain 🌋 volcano
        🏕️ camping 🏖️ beach 🏜️ desert 🏝️ island 🏞️ park 🏟️ stadium 🏛️ classical 🏗️ construction
        🏠 house 🏡 home-garden 🏢 office 🏥 hospital 🏦 bank 🏨 hotel 🏫 school 🗽 statue-liberty
        🗼 tower 🎡 ferris-wheel 🎢 roller-coaster ⛲ fountain ⛺ tent 🌁 fog 🌃 night 🌅 sunrise
        🌇 sunset 🌉 bridge 🌌 milky-way 🎆 fireworks 🎇 sparkler 🌈 rainbow ☀️ sun 🌙 moon
        """,
    ),
    group(
        "Objects",
        """
        ⌚ watch 📱 phone 💻 laptop ⌨️ keyboard 🖥️ desktop 🖨️ printer 🖱️ mouse-device 💽 disk
        💾 floppy-save 💿 cd 📀 dvd 📷 camera 📸 camera-flash 📹 video-camera 📺 tv 📻 radio
        🔋 battery 🔌 plug 💡 bulb-idea 🔦 torch 🕯️ candle 🧯 extinguisher 🛢️ oil 💸 money-wings
        💵 dollar 💴 yen 💶 euro 💷 pound 💰 money-bag 💳 card 🧾 receipt 💎 gem
        ⚖️ scales 🔧 wrench 🔨 hammer 🛠️ tools ⚙️ gear 🧰 toolbox 🧲 magnet 🔫 water-pistol
        💊 pill 🩹 bandage 🩺 stethoscope 🚪 door 🛏️ bed 🛋️ couch 🚽 toilet 🚿 shower
        🧴 lotion 🧻 toilet-paper 🧼 soap 🧽 sponge 🔑 key 🔒 locked 🔓 unlocked 🔔 bell
        📚 books 📖 open-book 📝 memo-note ✏️ pencil 🖊️ pen 📌 pin 📎 paperclip ✂️ scissors
        📁 folder 📅 calendar 📈 chart-up 📉 chart-down 📊 bar-chart 🗑️ trash 📦 package 📮 postbox
        """,
    ),
    group(
        "Symbols & flags",
        """
        ❤️ red-heart 🧡 orange-heart 💛 yellow-heart 💚 green-heart 💙 blue-heart 💜 purple-heart 🖤 black-heart 🤍 white-heart
        🤎 brown-heart 💔 broken-heart ❣️ heart-exclamation 💕 two-hearts 💞 revolving-hearts 💓 beating-heart 💗 growing-heart 💖 sparkling-heart
        💘 heart-arrow 💝 heart-ribbon 💯 hundred 💢 anger 💥 boom-collision 💫 dizzy-star 💦 sweat-drops 💨 dash
        🔥 fire 🌟 glowing-star ⭐ star ✨ sparkles ⚡ zap-lightning ☄️ comet ❄️ snowflake ☃️ snowman
        ✅ check 🔴 red-circle 🟠 orange-circle 🟡 yellow-circle 🟢 green-circle 🔵 blue-circle 🟣 purple-circle ⚫ black-circle
        ❌ cross-no ⛔ no-entry 🚫 prohibited ⚠️ warning ‼️ double-exclamation ❓ question ❗ exclamation 🔞 eighteen
        ♻️ recycle 🔄 refresh 🔝 top 🆕 new 🆗 ok 🆙 up 🆒 cool 🈶 free
        🎉 tada-party 🎊 confetti 🎈 balloon 🎁 gift 🎀 ribbon 🏳️‍🌈 rainbow-flag 🏁 chequered-flag 🚩 flag
        """,
    ),
)

/** Every glyph in the catalog, for the picker's search. */
val ALL_EMOJI: List<Emoji> = EMOJI_GROUPS.flatMap { it.emoji }

/** Matches on the keywords, with dashes read as spaces so "thumbs up" finds `thumbs-up`. */
fun Emoji.matches(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return glyph == needle || keywords.replace('-', ' ').contains(needle)
}
