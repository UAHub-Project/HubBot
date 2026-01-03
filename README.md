# HubBot

HubBot is Discord bot used by [UAHub Discord server](https://discord.gg/hcTgvbFBWZ)

# Features
- Rank system
- Music player
- Permission system

# Getting started
## Build
There is no releases for now, so you need to build bot first.

You need Java 17 or above to build project
```shell
# Clone repository
git clone https://github.com/UAHub-Project/HubBot.git
cd HubBot/

chmod +x ./gradlew

./gradlew botEntity:shadowJar
# Then check botEntity/build/libs folder there should be a jar file
ls botEntity/build/libs
```

## Configuration

To run bot you need run this command
```shell
java -cp botEntity.jar ua.beengoo.uahub.bot.HubBot
```
First run will create `config.json` file and will close with a lot of errors, this is fine, trust me.

You will see something like that:
```json
{
  "token": "place your bot token here",
  "targetGuildId": "970251105558745118",
  "postgresqlURL": "put",
  "postgresqlUser": "it",
  "postgresqlPassword": "here",
  "language": "en",
  "youtubeOAuth": {
    "refreshToken": "",
    "enabled": false
  },
  "remoteCipher": {
    "enabled": false,
    "url": "http://localhost:8001",
    "password": "your_secret_password"
  }
}
```

- `token` - Your bot token
- `targetGuildId` - Since bot is design for single guild, you must specify your Discord server on which it will be used
- `postgresqlURL`, `postgresqlUser` and `postgresqlPassword` - HubBot require PostgresQL to store data, for now this is only option.
- `language` - Bot interface language: `en - English (US)`, `uk - Ukrainian`
- `youtubeOAuth` - Used to link burner Google account [see YouTube Configuration](#youtube-configuration)
- `remoteCipher` - yt-dlp wrapper server credentials [see YouTube Configuration](#youtube-configuration)


## YouTube Configuration

Since YouTube constantly trying to block video downloaders for *safety* reasons, native Lavaplayer every time just stop working with YouTube.
So only solution is to set up [yt-cipher](https://github.com/kikkia/yt-cipher) server.


Also to bypass age restricted content you need to use Google account, so bot can have access to almost everything on YouTube

### Setup Google Account
HubBot has utility to simply fetch OAuth2 refresh token from Google account, run command below to start it, and follow instructions
```shell
java -cp botEntity.jar ua.beengoo.uahub.bot.HubBot --utility yto-helper
```
> [!NOTE]
> Do not use your main Google account, use burner instead!

