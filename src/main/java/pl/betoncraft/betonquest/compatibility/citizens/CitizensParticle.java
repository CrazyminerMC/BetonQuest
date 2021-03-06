/**
 * BetonQuest - advanced quests for Bukkit
 * Copyright (C) 2016  Jakub "Co0sh" Sapalski
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.betoncraft.betonquest.compatibility.citizens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import de.slikey.effectlib.util.DynamicLocation;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import pl.betoncraft.betonquest.BetonQuest;
import pl.betoncraft.betonquest.ConditionID;
import pl.betoncraft.betonquest.ObjectNotFoundException;
import pl.betoncraft.betonquest.compatibility.effectlib.EffectLibIntegrator;
import pl.betoncraft.betonquest.compatibility.protocollib.NPCHider;
import pl.betoncraft.betonquest.config.Config;
import pl.betoncraft.betonquest.config.ConfigPackage;
import pl.betoncraft.betonquest.utils.PlayerConverter;

/**
 * Displays a particle above NPCs with conversations.
 *
 * @author Jakub Sapalski
 */
public class CitizensParticle extends BukkitRunnable {

    private Set<Integer> npcss = new HashSet<>();
    private Map<UUID, Map<Integer, NPCs>> players = new HashMap<>();
    private Map<String, Effect> effects = new HashMap<>();
    private List<NPCs> NPCs = new ArrayList<>();
    private static CitizensParticle instance;
    private int interval;
    private int tick = 0;
    private boolean enabled = false;

    public CitizensParticle() {
        instance = this;

        // loop across all packages
        for (ConfigPackage pack : Config.getPackages().values()) {

            // load all NPC ids
            for (String npcID : pack.getMain().getConfig().getConfigurationSection("npcs").getKeys(false)) {
                try {
                    npcss.add(Integer.parseInt(npcID));
                } catch (NumberFormatException ignored) {
                }
            }

            // npc_effects contains all effects for NPCs
            ConfigurationSection npcSection = pack.getCustom().getConfig().getConfigurationSection("npc_effects");

            // if it's not defined then we're not displaying effects
            if (npcSection == null) {
                continue;
            }

            // load the condition check interval
            interval = npcSection.getInt("check_interval", 100);

            for (String key : npcSection.getKeys(false)) {
                ConfigurationSection settings = npcSection.getConfigurationSection(key);

                if (settings == null) {
                    continue;
                }

                NPCs npcs = new NPCs();

                //EXAMPLES

                /*particle_effects:
                    quest:            <-------------|---->EffectType
                         disabled: 'false'          |
                         class: WarpEffect          |
                         interval: 100              |
                         iterations: 6              |
                         particle: dragonbreath     |
                         grow: 0.14                 |
                         radius: 1                  |
                                                    |
                                                    |
                npc_effects:                        |
                    0a:                             |
                       effect: quest  <-------------|
                       npcs: '204'
                       conditions: '_0accept,!_0receipt,!_0end'*/


                npcs.effect = settings.getString("effect"); //take the NPC's effect and save it

                npcs.npcs = new HashSet<>();

                // load all NPCs for which this effect can be displayed
                for (String npc : settings.getString("npcs").split(",")) {
                    npcs.npcs.add(Integer.valueOf(npc));
                }

                // if no NPCs are selected, get all the NPCs
                if (npcs.npcs.isEmpty()) {
                    npcs.def = true;
                }

                // load all conditions
                npcs.conditions = new ArrayList<>();

                String[] conditions = settings.getString("conditions").split(",");
                for (String condition : conditions) {
                    try {
                        npcs.conditions.add(new ConditionID(pack, condition));
                    } catch (ObjectNotFoundException ignored) {
                    }
                }

                NPCs.add(npcs);
            }

            // npc_effects contains all effects for NPCs
            ConfigurationSection particleSection = pack.getCustom().getConfig().getConfigurationSection("particle_effects");

            // if it's not defined then we're not displaying effects
            if (particleSection == null) {
                continue;
            }

            // there's a setting to disable effects altogether. If the effect is disabled, skip it
            if ("true".equalsIgnoreCase(particleSection.getString("disabled"))) {
                continue;
            }

            // loading all effects
            for (String key : particleSection.getKeys(false)) {
                ConfigurationSection settings = particleSection.getConfigurationSection(key);

                // if the key is not a configuration npcSection then it's not an effect
                if (settings == null) {
                    continue;
                }

                Effect effect = new Effect();

                // the type of the effect, it's required
                effect.name = settings.getString("class");
                if (effect.name == null) {
                    continue;
                }

                // load the interval between animations
                effect.interval = settings.getInt("interval", 20);

                // set the effect settings
                effect.settings = settings;

                // add Effect
                effects.put(key, effect);
            }
        }
        runTaskTimer(BetonQuest.getInstance(), 1, 1);
        enabled = true;
    }

    private class Effect {

        private String name;
        private int interval;
        private ConfigurationSection settings;
    }

    private class NPCs {

        private Set<Integer> npcs;
        private boolean def;
        private List<ConditionID> conditions;
        private String effect;

    }

    @Override
    public void run() {

        // check conditions if it's the time
        if (tick % interval == 0) {
            checkConditions();
        }

        // run effects for all players
        activateEffects();

        tick++;
    }

    private void checkConditions() {

        // clear previous assignments
        players.clear();

        // every player needs to generate their assignment
        for (Player player : Bukkit.getOnlinePlayers()) {

            // create an assignment map
            Map<Integer, NPCs> assignments = new HashMap<>();

            // handle all effects

            npcs:
            for (NPCs npcs : NPCs) {

                // skip the effect if conditions are not met
                for (ConditionID condition : npcs.conditions) {
                    if (!BetonQuest.condition(PlayerConverter.getID(player), condition)) {
                        continue npcs;
                    }
                }

                // determine which NPCs should receive this effect | if def == true, set the effect on every NPC. Otherwise only on npcs.npcs
                Collection<Integer> applicableNPCs = npcs.def ? new HashSet<>(npcss) : npcs.npcs;

                // assign this effect to all NPCs which don't have already assigned effects
                for (Integer npc : applicableNPCs) {
                    if (!assignments.containsKey(npc)) {
                        assignments.put(npc, npcs);
                    }
                }

            }

            // put assignments into the main map
            players.put(player.getUniqueId(), assignments);
        }
    }

    private void activateEffects() {

        // display effects for all players
        for (Player player : Bukkit.getOnlinePlayers()) {

            // get NPC-effect assignments for this player
            Map<Integer, NPCs> assignments = players.get(player.getUniqueId());

            // skip if there are no assignments for this player
            if (assignments == null) {
                continue;
            }

            // display effects on all NPCs
            for (Entry<Integer, NPCs> entry : assignments.entrySet()) {
                Integer id = entry.getKey();
                NPCs npcs = entry.getValue();
                Effect effect = effects.get(npcs.effect);

                // skip this effect if it's not its time
                if (tick % effect.interval != 0) {
                    continue;
                }

                // get the NPC from its ID
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);

                // skip if there are no such NPC or it's not spawned or not visible
                if (npc == null || !npc.isSpawned() ||
                        (NPCHider.getInstance() != null && NPCHider.getInstance().isInvisible(player, npc))) {
                    continue;
                }

                // prepare effect location
                Location loc = npc.getStoredLocation().clone();
                loc.setPitch(-90);

                // fire the effect
                EffectLibIntegrator.getEffectManager().start(
                        effect.name,
                        effect.settings,
                        new DynamicLocation(loc, null),
                        new DynamicLocation(null, null),
                        null,
                        player);

            }
        }
    }

    /**
     * Reloads the particle effect
     */
    public static void reload() {
        if (instance.enabled) {
            instance.cancel();
        }
        new CitizensParticle();
    }

}