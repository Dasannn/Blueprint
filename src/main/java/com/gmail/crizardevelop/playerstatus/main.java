package com.gmail.crizardevelop.playerstatus;

import java.io.File;
import java.util.ArrayList;

import java.util.List;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;

public class main extends JavaPlugin {

    public static String tierm5;
    public static String tierm4;
    public static String tierm3;
    public static String tierm2;
    public static String tierm1;
    public static String tier0;
    public static String tier1;
    public static String tier2;
    public static String tier3;
    public static String tier4;
    public static String tier5;

    public static String namem5;
    public static String namem4;
    public static String namem3;
    public static String namem2;
    public static String namem1;
    public static String name0;
    public static String name1;
    public static String name2;
    public static String name3;
    public static String name4;
    public static String name5;

    public static int repm5;
    public static int repm4;
    public static int repm3;
    public static int repm2;
    public static int repm1;
    public static int rep0;
    public static int rep1;
    public static int rep2;
    public static int rep3;
    public static int rep4;
    public static int rep5;

    String reputationPrefix = "Reputación · ";
    List<String> storageCoord = new ArrayList<>();
    @Override
    public void onEnable() {
        loadCommand();
        loadEvent();

        File config = new File(getDataFolder() + File.separator + "config.yml");
        if (!config.exists()) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }

        loadStringPrefix();
        loadStringNames();
        loadIntRep();
    }

    @Override
    public void onDisable() {

    }

    public void sendMessageToPlayer(Player reciever, String msg) {
        reciever.sendMessage(msg);
    }

    private void loadCommand() {
        this.getCommand("pstatus").setExecutor(new Commands(this));
        this.getCommand("reputation").setExecutor(new Commands(this));
        this.getCommand("utils").setExecutor(new Commands(this));
    }

    private void loadEvent() {
        this.getServer().getPluginManager().registerEvents(new EventManager(this), this);
    }

    public void preVoteForPlayer(Player playerSender, Player playerAffected, boolean plus) {
        List<String> listSenderVotedGood;
        List<String> listSenderVotedBad;

        listSenderVotedGood = getConfig().getStringList("playerVoted." + playerSender.getName() + ".good");
        listSenderVotedBad = getConfig().getStringList("playerVoted." + playerSender.getName() + ".bad");


        int playerSenderReputation = getConfig().getInt("playerList." + playerSender.getUniqueId() + ".reputation");
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();

        //Se eliminan primero las reputaciones previas
        boolean alreadyVotedGood = listSenderVotedGood.contains(playerAffected.getName());
        boolean alreadyVotedBad = listSenderVotedBad.contains(playerAffected.getName());

        if (plus) {

            if (alreadyVotedGood) {
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "No puedes volver a afectar positivamente la reputación de " + playerAffected.getName());
            } else if (alreadyVotedBad) {
                //Se elimina el usuario de la lista bad
                listSenderVotedBad.remove(playerAffected.getName());
                //Se agrega el usuario a la lista good
                listSenderVotedGood.add(playerAffected.getName());
                addReputationPoint(playerAffected);
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "Afectaste la reputación de " + playerAffected.getName() + ChatColor.GREEN + " positivamente");

            } else { //Si anteriormente el jugador no ha votado por el afectado
                listSenderVotedGood.add(playerAffected.getName());
                addReputationPoint(playerAffected);
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "Afectaste la reputación de " + playerAffected.getName() + ChatColor.GREEN + " positivamente");
            }
        }

        if (!plus) {
            if (alreadyVotedBad) {
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "No puedes volver a afectar negativamente la reputación de " + playerAffected.getName());
            } else if (alreadyVotedGood) {
                //Se elimina el usuario de la lista good
                listSenderVotedGood.remove(playerAffected.getName());
                //Se agrega el usuario a la lista bad
                listSenderVotedBad.add(playerAffected.getName());
                removeReputationPoint(playerAffected);
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "Afectaste la reputación de " + playerAffected.getName() + ChatColor.RED + " negativamente");
            } else { //Si anteriormente el jugador no ha votado por el afectado
                listSenderVotedBad.add(playerAffected.getName());
                removeReputationPoint(playerAffected);
                playerSender.sendMessage(ChatColor.YELLOW + reputationPrefix + ChatColor.GRAY + "Afectaste la reputación de " + playerAffected.getName() + ChatColor.RED + " negativamente");
            }
        }


        //Sea cual sea la actualización, se actualizan las dos listas
        getConfig().set(("playerVoted." + playerSender.getName() + ".good"), listSenderVotedGood);
        getConfig().set(("playerVoted." + playerSender.getName() + ".bad"), listSenderVotedBad);
        saveConfig();
    }

    public void removeVoteForPlayer(Player sender, Player affected) {
        List<String> listSenderVoted = new ArrayList<>();
        List<String> listSenderVoted2 = new ArrayList<>();
        listSenderVoted = getConfig().getStringList("playerVoted." + sender.getUniqueId() + ".good");
        listSenderVoted2 = getConfig().getStringList("playerVoted." + sender.getUniqueId() + ".bad");
        if (listSenderVoted.contains(affected.getUniqueId().toString()) || listSenderVoted2.contains(affected.getUniqueId().toString())) {
            listSenderVoted.remove(affected.getUniqueId().toString());
            getConfig().set(("playerVoted." + sender.getUniqueId() + ".good"), listSenderVoted);
            getConfig().set(("playerVoted." + sender.getUniqueId() + ".bad"), listSenderVoted2);
            saveConfig();
            removeReputationPoint(affected);
            //sender.sendMessage(ChatColor.GREEN+"Cambiaste tu reputación a neutral hacia "+affected.getName());
        }
        //else sender.sendMessage(ChatColor.RED+"No has afectado la reputación de este jugador antes");
    }

    public void loadStringPrefix() {
        tierm5 = getConfig().getString("tier-5.prefix").replace("&", "§");
        tierm4 = getConfig().getString("tier-4.prefix").replace("&", "§");
        tierm3 = getConfig().getString("tier-3.prefix").replace("&", "§");
        tierm2 = getConfig().getString("tier-2.prefix").replace("&", "§");
        tierm1 = getConfig().getString("tier-1.prefix").replace("&", "§");
        tier0 = getConfig().getString("tier0.prefix").replace("&", "§");
        tier1 = getConfig().getString("tier1.prefix").replace("&", "§");
        tier2 = getConfig().getString("tier2.prefix").replace("&", "§");
        tier3 = getConfig().getString("tier3.prefix").replace("&", "§");
        tier4 = getConfig().getString("tier4.prefix").replace("&", "§");
        tier5 = getConfig().getString("tier5.prefix").replace("&", "§");
    }

    public void loadStringNames() {
        namem5 = getConfig().getString("tier-5.name").replace("&", "§");
        namem4 = getConfig().getString("tier-4.name").replace("&", "§");
        namem3 = getConfig().getString("tier-3.name").replace("&", "§");
        namem2 = getConfig().getString("tier-2.name").replace("&", "§");
        namem1 = getConfig().getString("tier-1.name").replace("&", "§");
        name0 = getConfig().getString("tier0.name").replace("&", "§");
        name1 = getConfig().getString("tier1.name").replace("&", "§");
        name2 = getConfig().getString("tier2.name").replace("&", "§");
        name3 = getConfig().getString("tier3.name").replace("&", "§");
        name4 = getConfig().getString("tier4.name").replace("&", "§");
        name5 = getConfig().getString("tier5.name").replace("&", "§");
    }

    public void loadIntRep() {
        repm5 = getConfig().getInt("tier-5.repRequired");
        repm4 = getConfig().getInt("tier-4.repRequired");
        repm3 = getConfig().getInt("tier-3.repRequired");
        repm2 = getConfig().getInt("tier-2.repRequired");
        repm1 = getConfig().getInt("tier-1.repRequired");
        rep0 = getConfig().getInt("tier0.repRequired");
        rep1 = getConfig().getInt("tier1.repRequired");
        rep2 = getConfig().getInt("tier2.repRequired");
        rep3 = getConfig().getInt("tier3.repRequired");
        rep4 = getConfig().getInt("tier4.repRequired");
        rep5 = getConfig().getInt("tier5.repRequired");
    }

    public void updatePlayerPrefix(Player p) {
        p.setDisplayName("");
        p.setPlayerListName("");
        p.setCustomName("");

        int playerReputation = getConfig().getInt("playerList." + p.getUniqueId() + ".reputation");
        int typeStatus = 0;

        if (playerReputation <= repm5) {
            typeStatus = -5;
            String tittleStatus = getConfig().getString("tier-5.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation <= repm4 && playerReputation > repm5) {
            typeStatus = -4;
            String tittleStatus = getConfig().getString("tier-4.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation <= repm3 && playerReputation > repm4) {
            typeStatus = -3;
            String tittleStatus = getConfig().getString("tier-3.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation <= repm2 && playerReputation > repm3) {
            typeStatus = -2;
            String tittleStatus = getConfig().getString("tier-2.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation <= repm1 && playerReputation > repm2) {
            typeStatus = -1;
            String tittleStatus = getConfig().getString("tier-1.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation < rep1 && playerReputation > repm1) {
            typeStatus = 0;
            String tittleStatus = getConfig().getString("tier0.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation >= rep1 && playerReputation < rep2) {
            typeStatus = 1;
            String tittleStatus = getConfig().getString("tier1.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation >= rep2 && playerReputation < rep3) {
            typeStatus = 2;
            String tittleStatus = getConfig().getString("tier2.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation >= rep3 && playerReputation < rep4) {
            typeStatus = 3;
            String tittleStatus = getConfig().getString("tier3.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation >= rep4 && playerReputation < rep5) {
            typeStatus = 4;
            String tittleStatus = getConfig().getString("tier4.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        } else if (playerReputation >= rep5) {
            typeStatus = 5;
            String tittleStatus = getConfig().getString("tier5.name");
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", typeStatus);
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", tittleStatus);
            saveConfig();
        }

        switch (typeStatus) {
            case (-5):

                p.setDisplayName(tierm5 + p.getName());
                p.setPlayerListName(tierm5 + p.getName());
                p.setCustomName(tierm5 + p.getName());
                break;
            case (-4):
                p.setDisplayName(tierm4 + p.getName());
                p.setPlayerListName(tierm4 + p.getName());
                p.setCustomName(tierm4 + p.getName());
                break;
            case (-3):
                p.setDisplayName(tierm3 + p.getName());
                p.setPlayerListName(tierm3 + p.getName());
                p.setCustomName(tierm3 + p.getName());
                break;
            case (-2):
                p.setDisplayName(tierm2 + p.getName());
                p.setPlayerListName(tierm2 + p.getName());
                p.setCustomName(tierm2 + p.getName());
                break;
            case (-1):
                p.setDisplayName(tierm1 + p.getName());
                p.setPlayerListName(tierm1 + p.getName());
                p.setCustomName(tierm1 + p.getName());
                break;
            case 0:
                p.setDisplayName(tier0 + p.getName());
                p.setPlayerListName(tier0 + p.getName());
                p.setCustomName(tier0 + p.getName());
                break;
            case 1:
                p.setDisplayName(tier1 + p.getName());
                p.setPlayerListName(tier1 + p.getName());
                p.setCustomName(tier1 + p.getName());
                break;
            case 2:
                p.setDisplayName(tier2 + p.getName());
                p.setPlayerListName(tier2 + p.getName());
                p.setCustomName(tier2 + p.getName());
                break;
            case 3:
                p.setDisplayName(tier3 + p.getName());
                p.setPlayerListName(tier3 + p.getName());
                p.setCustomName(tier3 + p.getName());
                break;
            case 4:
                p.setDisplayName(tier4 + p.getName());
                p.setPlayerListName(tier4 + p.getName());
                p.setCustomName(tier4 + p.getName());
                break;
            case 5:
                p.setDisplayName(tier5 + p.getName());
                p.setPlayerListName(tier5 + p.getName());
                p.setCustomName(tier5 + p.getName());
                break;
        }
    }

    public void removeReputationPoint(Player p) {
        int reputation = getConfig().getInt("playerList." + p.getUniqueId() + ".reputation");
        getConfig().set("playerList." + p.getUniqueId() + ".reputation", reputation - 1);
        saveConfig();
        updatePlayerPrefix(p);
    }

    public void addReputationPoint(Player p) {
        int reputation = getConfig().getInt("playerList." + p.getUniqueId() + ".reputation");
        getConfig().set("playerList." + p.getUniqueId() + ".reputation", reputation + 1);
        saveConfig();
        updatePlayerPrefix(p);

    }

    public void setReputationPoint(Player p, int rep) {
        getConfig().set("playerList." + p.getUniqueId() + ".reputation", rep);
        saveConfig();
        updatePlayerPrefix(p);
    }

    public void setDefaultConfig(Player p) {
        if (getConfig().get("playerList." + p.getUniqueId()) == null) {
            getConfig().set("playerList." + p.getUniqueId() + ".name", p.getName());
            getConfig().set("playerList." + p.getUniqueId() + ".tittle", getConfig().getString("playerList." + p.getUniqueId() + ".tittle"));
            getConfig().set("playerList." + p.getUniqueId() + ".reputation", 0);
            getConfig().set("playerList." + p.getUniqueId() + ".typeStatus", 0);

            getConfig().set("playerVoted." + p.getUniqueId() + ".name", p.getName());
            List<String> emptyList = new ArrayList<>();
            getConfig().set("playerVoted." + p.getUniqueId() + ".good", emptyList);
            getConfig().set("playerVoted." + p.getUniqueId() + ".bad", emptyList);
            saveConfig();
            updatePlayerPrefix(p);
        }
    }

    public void showStatusInfo(Player sender, Player pinfo, boolean isSelfPlayer) {
        if (isSelfPlayer == true) {
            sender.sendMessage(ChatColor.AQUA + "Estatus social " + ChatColor.WHITE + "· Información de " + pinfo.getName() + "\n"
                    //+ ChatColor.YELLOW + "Jugador: " + ChatColor.GRAY + pinfo.getName() + "\n"
                    + ChatColor.YELLOW + "La gente le conoce como: " + ChatColor.GRAY + getConfig().getString("playerList." + sender.getUniqueId() + ".tittle") + "\n"
                    + ChatColor.YELLOW + "Cantidad de reputación: " + ChatColor.GRAY + getConfig().getInt("playerList." + sender.getUniqueId() + ".reputation") + "\n"
                    + ChatColor.YELLOW + "Código de estatus social: " + ChatColor.GRAY + getConfig().getInt("playerList." + sender.getUniqueId() + ".typeStatus")
            );
        }
        if (isSelfPlayer == false) {
            sender.sendMessage(ChatColor.AQUA + "Estatus social " + ChatColor.WHITE + "· Información de " + pinfo.getName() + "\n"
                    //+ ChatColor.YELLOW + "Jugador: " + ChatColor.GRAY + pinfo.getName() + "\n"
                    + ChatColor.YELLOW + "La gente le conoce como:  " + ChatColor.GRAY + getConfig().getString("playerList." + pinfo.getUniqueId() + ".tittle") + "\n"
                    + ChatColor.YELLOW + "Cantidad de reputación: " + ChatColor.GRAY + getConfig().getInt("playerList." + pinfo.getUniqueId() + ".reputation") + "\n"
                    + ChatColor.YELLOW + "Código de estatus social: " + ChatColor.GRAY + getConfig().getInt("playerList." + pinfo.getUniqueId() + ".typeStatus")
            );
        }
    }

    public void evaluateAllPlayers(Object[] allPlayerList) {
        List<String> criminalesList = new ArrayList<String>();
        List<String> buenagenteList = new ArrayList<String>();
        for (Object objectPlayer : allPlayerList) {
            if (this.isBadPlayer((Player) objectPlayer)) {
                criminalesList.add(((Player) objectPlayer).getName());
            } else {
                buenagenteList.add(((Player) objectPlayer).getName());
            }
        }
        String listaOrdenadaCriminales = "";
        for (String criminal : criminalesList) {
            listaOrdenadaCriminales += ChatColor.RED + "" + ChatColor.BOLD + "" + ChatColor.UNDERLINE + criminal + ", ";
        }
        String listaOrdenadaBuenaGente = "";
        for (String buenaGente : buenagenteList) {
            listaOrdenadaBuenaGente += ChatColor.GREEN + "" + ChatColor.BOLD + "" + ChatColor.UNDERLINE + buenaGente + ", ";
        }
        String messageBadPerson = "";
        String messageGoodPerson = "";

        if (criminalesList.isEmpty()) {
            messageBadPerson = getConfig().getString("defaultMessages.thereAreGoodPerson");
            Bukkit.getServer().broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + messageBadPerson);
        } else {
            messageGoodPerson = getConfig().getString("defaultMessages.thereAreBadPerson");
            Bukkit.getServer().broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + messageGoodPerson);
        }
        if (buenagenteList.isEmpty()) {
            messageBadPerson = getConfig().getString("defaultMessages.weekBadPersonES");
            Bukkit.getServer().broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + messageBadPerson);
            Bukkit.getServer().broadcastMessage(listaOrdenadaCriminales.substring(0, listaOrdenadaCriminales.length() - 2));
        } else {
            messageGoodPerson = getConfig().getString("defaultMessages.weekGoodPersonES");
            Bukkit.getServer().broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + messageGoodPerson);
            Bukkit.getServer().broadcastMessage(listaOrdenadaBuenaGente.substring(0, listaOrdenadaBuenaGente.length() - 2));
        }
    }

    public Boolean isBadPlayer(Player player) {
        int reputacionActual = getConfig().getInt("playerList." + player.getUniqueId() + ".reputation");
        String userActualIterado = player.getName();
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        if (reputacionActual <= -5) {
            player.sendMessage("Has sido sancionado por tu reputacion, se descontaran $" + getConfig().getInt("tier-5.commandWhenPlayerIsEvaluated") + " de tu cuenta.");
            String command = "eco take " + userActualIterado + " " + getConfig().getInt("tier-5.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return true;
        } else if (reputacionActual == -4) {
            player.sendMessage("Has sido sancionado por tu reputacion, se descontaran $" + getConfig().getInt("tier-4.commandWhenPlayerIsEvaluated") + " de tu cuenta.");
            String command = "eco take " + userActualIterado + " " + getConfig().getInt("tier-4.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return true;
        } else if (reputacionActual == -3) {
            player.sendMessage("Has sido sancionado por tu reputacion, se descontaran $" + getConfig().getInt("tier-3.commandWhenPlayerIsEvaluated") + " de tu cuenta.");
            String command = "eco take " + userActualIterado + " " + getConfig().getInt("tier-3.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return true;
        } else if (reputacionActual == -2) {
            player.sendMessage("Has sido sancionado por tu reputacion, se descontaran $" + getConfig().getInt("tier-2.commandWhenPlayerIsEvaluated") + " de tu cuenta.");
            String command = "eco take " + userActualIterado + " " + getConfig().getInt("tier-2.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return true;
        } else if (reputacionActual == -1) {
            player.sendMessage("Has sido sancionado por tu reputacion, se descontaran $" + getConfig().getInt("tier-1.commandWhenPlayerIsEvaluated") + " de tu cuenta.");
            String command = "eco take " + userActualIterado + " " + getConfig().getInt("tier-1.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return true;


        } else if (reputacionActual == 0) {
            player.sendMessage("Mejora como persona.");
            return false;


        } else if (reputacionActual == 1) {
            player.sendMessage("Has sido premiado por tu reputacion, se agregaran $" + getConfig().getInt("tier1.commandWhenPlayerIsEvaluated") + " a tu cuenta.");
            String command = "eco give " + userActualIterado + " " + getConfig().getInt("tier1.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return false;
        } else if (reputacionActual == 2) {
            player.sendMessage("Has sido premiado por tu reputacion, se agregaran $" + getConfig().getInt("tier2.commandWhenPlayerIsEvaluated") + " a tu cuenta.");
            String command = "eco give " + userActualIterado + " " + getConfig().getInt("tier2.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return false;
        } else if (reputacionActual == 3) {
            player.sendMessage("Has sido premiado por tu reputacion, se agregaran $" + getConfig().getInt("tier3.commandWhenPlayerIsEvaluated") + " a tu cuenta.");
            String command = "eco give " + userActualIterado + " " + getConfig().getInt("tier3.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return false;
        } else if (reputacionActual == 4) {
            player.sendMessage("Has sido premiado por tu reputacion, se agregaran $" + getConfig().getInt("tier4.commandWhenPlayerIsEvaluated") + " a tu cuenta.");
            String command = "eco give " + userActualIterado + " " + getConfig().getInt("tier4.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return false;
        } else if (reputacionActual >= 5) {
            player.sendMessage("Has sido premiado por tu reputacion, se agregaran $" + getConfig().getInt("tier5.commandWhenPlayerIsEvaluated") + " a tu cuenta.");
            String command = "eco give " + userActualIterado + " " + getConfig().getInt("tier5.commandWhenPlayerIsEvaluated");
            Bukkit.dispatchCommand(console, command);
            return false;
        }
        return false;
    }

    public void checkDeathAndReputationPlayer(Player player){
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        if(isBadPlayer(player)) {

            String listContent = getConfig().getString("defaultCodes.deathPlayer.badPerson.command");
            listContent = listContent.replaceAll("\\[|\\]", "");
            String[] indivCommand = listContent.split("\\s*,\\s*");

            for (String comando : indivCommand) {
                String updatedCommand = comando.replace("?playername?", player.getName());
                Bukkit.dispatchCommand(console, updatedCommand);

            }
        }else{
            String listContent = getConfig().getString("defaultCodes.deathPlayer.goodPerson.command");
            listContent = listContent.replaceAll("\\[|\\]", "");
            String[] indivCommand = listContent.split("\\s*,\\s*");

            for (String comando : indivCommand) {
                String updatedCommand = comando.replace("?playername?", player.getName());
                Bukkit.dispatchCommand(console, updatedCommand);

            }
        }
    }
}

