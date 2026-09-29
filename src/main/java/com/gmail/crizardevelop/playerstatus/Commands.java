package com.gmail.crizardevelop.playerstatus;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Commands implements CommandExecutor {

    private PrefixManager pm;

    private final main main;

    Commands(main main) {
        this.main = main;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player playerSender = (Player) sender;
        // /pstatus
        if (label.equalsIgnoreCase("pstatus") && args.length > 0) {
            // pstatus evaluate
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("evaluate")) {
                    if (sender.hasPermission("pstatus.evaluate")) {
                        main.evaluateAllPlayers(Bukkit.getOnlinePlayers().toArray());
                        return true;
                    }
                }
            }

            // pstatus info
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("info")) {
                    if (sender.hasPermission("pstatus.show")) {
                        main.showStatusInfo(playerSender, playerSender, true);
                        return true;
                    }
                }
            }

            if (args.length == 2) {
                // pstatsu info <player>
                Player playerReciever = Bukkit.getPlayer(args[1]);
                if (args[0].equalsIgnoreCase("info")) {
                    if (sender.hasPermission("pstatus.showOtherPlayers")) {
                        main.showStatusInfo(playerSender, playerReciever, false);
                        return true;
                    }
                }

                // pstatsu add <player>
                if (args[0].equalsIgnoreCase("add")) {
                    if (sender.hasPermission("pstatus.addRemoveRep")) {
                        if (playerReciever != null && playerReciever instanceof Player) {
                            main.addReputationPoint(playerReciever);
                            main.updatePlayerPrefix(playerReciever);
                            return true;
                        }
                    }
                }
                // pstatus remove <player>
                if (args[0].equalsIgnoreCase("remove")) {
                    if (sender.hasPermission("pstatus.addRemoveRep")) {
                        if (playerReciever != null && playerReciever instanceof Player) {
                            main.removeReputationPoint(playerReciever);
                            main.updatePlayerPrefix(playerReciever);
                            return true;
                        }
                    }
                }
            }
            // pstatus set <player> <newReputation>
            if (args.length == 3) {
                Player playerReciever = Bukkit.getPlayer(args[1]);
                if (args[0].equalsIgnoreCase("set")) {
                    if (sender.hasPermission("pstatus.setReputation")) {
                        if (playerReciever != null && playerReciever instanceof Player) {
                            main.setReputationPoint(Bukkit.getPlayer(args[1]), Integer.parseInt(args[2]));
                            main.updatePlayerPrefix(Bukkit.getPlayer(args[1]));
                            return true;
                        }
                    }
                }
            }
        }
        // Command: /reputation
        if (label.equalsIgnoreCase("reputation") && args.length > 0) {
            Player playerReciever = Bukkit.getPlayer(args[0]);
            if (playerReciever != null && playerReciever instanceof Player) {
                if (args.length == 2) {
                    if (sender.hasPermission("pstatus.giveReputation")) {
                        if (!playerReciever.getName().equals(playerSender.getName())) {
                            if (args[1].equalsIgnoreCase("+")) {
                                main.preVoteForPlayer(playerSender, playerReciever, true);
                                return true;
                                //true = +
                            } else if (args[1].equalsIgnoreCase("-")) {
                                main.preVoteForPlayer(playerSender, playerReciever, false);
                                return true;
                                //false = -
                            }
                        }
                    }
                }

                if (args.length == 1) {
                    if (sender.hasPermission("pstatus.viewReputation")) {
                        if (!playerReciever.getName().equals(playerSender.getName())) {
                            main.showStatusInfo(playerSender, playerReciever, false);
                            return true;
                        } else {
                            main.showStatusInfo(playerSender, playerSender, true);
                            return true;
                        }

                    }
                }
            }
        }

        return false;
    }
}
