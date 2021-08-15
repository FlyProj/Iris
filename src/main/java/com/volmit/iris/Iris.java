/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2021 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.volmit.iris;

import com.volmit.iris.core.*;
import com.volmit.iris.core.command.CommandIris;
import com.volmit.iris.core.command.PermissionIris;
import com.volmit.iris.core.command.studio.CommandIrisStudio;
import com.volmit.iris.core.command.world.CommandLocate;
import com.volmit.iris.core.link.IrisPapiExpansion;
import com.volmit.iris.core.link.MultiverseCoreLink;
import com.volmit.iris.core.link.MythicMobsLink;
import com.volmit.iris.core.link.OraxenLink;
import com.volmit.iris.core.nms.INMS;
import com.volmit.iris.core.project.loader.IrisData;
import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.object.biome.IrisBiome;
import com.volmit.iris.engine.object.biome.IrisBiomeCustom;
import com.volmit.iris.engine.object.common.IrisWorld;
import com.volmit.iris.engine.object.compat.IrisCompat;
import com.volmit.iris.engine.object.dimensional.IrisDimension;
import com.volmit.iris.engine.platform.BukkitChunkGenerator;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.collection.KSet;
import com.volmit.iris.util.format.C;
import com.volmit.iris.util.format.Form;
import com.volmit.iris.util.function.NastyRunnable;
import com.volmit.iris.util.io.FileWatcher;
import com.volmit.iris.util.io.IO;
import com.volmit.iris.util.io.InstanceState;
import com.volmit.iris.util.io.JarScanner;
import com.volmit.iris.util.math.M;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.parallel.MultiBurst;
import com.volmit.iris.util.plugin.Metrics;
import com.volmit.iris.util.plugin.Permission;
import com.volmit.iris.util.plugin.VolmitPlugin;
import com.volmit.iris.util.plugin.VolmitSender;
import com.volmit.iris.util.reflect.ShadeFix;
import com.volmit.iris.util.scheduling.GroupedExecutor;
import com.volmit.iris.util.scheduling.J;
import com.volmit.iris.util.scheduling.Queue;
import com.volmit.iris.util.scheduling.ShurikenQueue;
import io.papermc.lib.PaperLib;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Date;

@SuppressWarnings("CanBeFinal")
public class Iris extends VolmitPlugin implements Listener {
    public static KList<GroupedExecutor> executors = new KList<>();
    public static Iris instance;
    public static BukkitAudiences audiences;
    public static ProjectManager proj;
    public static ConversionManager convert;
    public static WandManager wand;
    public static EditManager edit;
    public static CoreBoardManager board;
    public static MultiverseCoreLink linkMultiverseCore;
    public static OraxenLink linkOraxen;
    public static MythicMobsLink linkMythicMobs;
    public static CommandManager commandManager;
    public static TreeManager saplingManager;
    private static final Queue<Runnable> syncJobs = new ShurikenQueue<>();
    public static IrisCompat compat;
    public static FileWatcher configWatcher;
    private static VolmitSender sender;

    @Permission
    public static PermissionIris perm;

    @com.volmit.iris.util.plugin.Command
    public CommandIris commandIris;

    @com.volmit.iris.util.plugin.Command
    public CommandIrisStudio commandStudio;

    public Iris() {
        instance = this;
        INMS.get();
        IO.delete(new File("iris"));
        installDataPacks();
    }

    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        fixShading();
        sender = new VolmitSender(Bukkit.getConsoleSender());
        sender.setTag(getTag());
        instance = this;
        compat = IrisCompat.configured(getDataFile("compat.json"));
        proj = new ProjectManager();
        convert = new ConversionManager();
        wand = new WandManager();
        board = new CoreBoardManager();
        linkMultiverseCore = new MultiverseCoreLink();
        linkOraxen = new OraxenLink();
        linkMythicMobs = new MythicMobsLink();
        saplingManager = new TreeManager();
        edit = new EditManager();
        configWatcher = new FileWatcher(getDataFile("settings.json"));
        commandManager = new CommandManager();
        getServer().getPluginManager().registerEvents(new CommandLocate(), this);
        getServer().getPluginManager().registerEvents(new WandManager(), this);
        getServer().getPluginManager().registerEvents(new DolphinManager(), this);
        getServer().getPluginManager().registerEvents(new VillagerManager(), this);
        super.onEnable();
        Bukkit.getPluginManager().registerEvents(this, this);
        J.s(this::lateBind);
        splash();
    }

    public void onDisable() {
        if (IrisSettings.get().isStudio()) {
            Iris.debug("Studio Mode Active: Closing Projects");
            proj.close();

            for (World i : Bukkit.getWorlds()) {
                if (IrisToolbelt.isIrisWorld(i)) {
                    Iris.debug("Closing Platform Generator " + i.getName());
                    IrisToolbelt.access(i).close();
                }
            }

            for (GroupedExecutor i : executors) {
                Iris.debug("Closing Executor " + i.toString());
                i.closeNow();
            }
        }

        executors.clear();
        board.disable();
        Iris.debug("Cancelled all tasks");
        Bukkit.getScheduler().cancelTasks(this);
        Iris.debug("Unregistered all events");
        HandlerList.unregisterAll((Plugin) this);
        Iris.debug("Multiburst Shutting down");
        MultiBurst.burst.shutdown();
        Iris.debug("Iris Shutdown");
        super.onDisable();
    }

    public static void callEvent(Event e) {
        if (!e.isAsynchronous()) {
            J.s(() -> Bukkit.getPluginManager().callEvent(e));
        } else {
            Bukkit.getPluginManager().callEvent(e);
        }
    }

    public static KList<Object> initialize(String s, Class<? extends Annotation> slicedClass) {
        JarScanner js = new JarScanner(instance.getJarFile(), s);
        KList<Object> v = new KList<>();
        J.attempt(js::scan);
        for (Class<?> i : js.getClasses()) {
            if (slicedClass == null || i.isAnnotationPresent(slicedClass)) {
                try {
                    v.add(i.getDeclaredConstructor().newInstance());
                } catch (Throwable ignored) {

                }
            }
        }

        return v;
    }

    private void fixShading() {
        ShadeFix.fix(ComponentSerializer.class);
    }

    private void lateBind() {
        J.a(() -> PaperLib.suggestPaper(this));
        J.a(() -> IO.delete(getTemp()));
        J.a(this::bstats);
        J.ar(this::checkConfigHotload, 60);
        J.sr(this::tickQueue, 0);
        J.s(this::setupPapi);
    }

    private void setupPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new IrisPapiExpansion().register();
        }
    }

    public File getDatapacksFolder() {
        if (!IrisSettings.get().getGeneral().forceMainWorld.isEmpty()) {
            return new File(IrisSettings.get().getGeneral().forceMainWorld + "/datapacks");
        }

        File props = new File("server.properties");

        if (props.exists()) {
            try {
                KList<String> m = new KList<>(IO.readAll(props).split("\\Q\n\\E"));

                for (String i : m) {
                    if (i.trim().startsWith("level-name=")) {
                        return new File(i.trim().split("\\Q=\\E")[1] + "/datapacks");
                    }
                }
            } catch (IOException e) {
                Iris.reportError(e);
                e.printStackTrace();
            }
        }

        return null;
    }

    public void installDataPacks() {
        Iris.info("Checking Data Packs...");
        boolean reboot = false;
        File packs = new File("plugins/Iris/packs");
        File dpacks = getDatapacksFolder();

        if (dpacks == null) {
            Iris.error("Cannot find the datapacks folder! Please try generating a default world first maybe? Is this a new server?");
            return;
        }

        if (packs.exists()) {
            for (File i : packs.listFiles()) {
                if (i.isDirectory()) {
                    Iris.verbose("Checking Pack: " + i.getPath());
                    IrisData data = new IrisData(i);
                    File dims = new File(i, "dimensions");

                    if (dims.exists()) {
                        for (File j : dims.listFiles()) {
                            if (j.getName().endsWith(".json")) {
                                IrisDimension dim = data.getDimensionLoader().load(j.getName().split("\\Q.\\E")[0]);
                                Iris.verbose("  Checking Dimension " + dim.getLoadFile().getPath());
                                if (dim.installDataPack(() -> data, dpacks)) {
                                    reboot = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        Iris.info("Data Packs Setup!");
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public String getTag(String subTag) {
        return C.BOLD + "" + C.DARK_GRAY + "[" + C.BOLD + "" + C.IRIS + "Iris" + C.BOLD + C.DARK_GRAY + "]" + C.RESET + "" + C.GRAY + ": ";
    }

    private void checkConfigHotload() {
        if (configWatcher.checkModified()) {
            IrisSettings.invalidate();
            IrisSettings.get();
            configWatcher.checkModified();
            Iris.info("Hotloaded settings.json");
        }
    }

    public static void sq(Runnable r) {
        synchronized (syncJobs) {
            syncJobs.queue(r);
        }
    }

    private void tickQueue() {
        synchronized (Iris.syncJobs) {
            if (!Iris.syncJobs.hasNext()) {
                return;
            }

            long ms = M.ms();

            while (Iris.syncJobs.hasNext() && M.ms() - ms < 25) {
                try {
                    Iris.syncJobs.next().run();
                } catch (Throwable e) {
                    e.printStackTrace();
                    Iris.reportError(e);
                }
            }
        }
    }

    private void bstats() {
        if (IrisSettings.get().getGeneral().isPluginMetrics()) {
            J.s(() -> {
                Metrics m = new Metrics(Iris.instance, 8757);

                m.addCustomChart(new Metrics.SingleLineChart("custom_dimensions", ProjectManager::countUniqueDimensions));

                m.addCustomChart(new Metrics.SimplePie("using_custom_dimensions", () -> ProjectManager.countUniqueDimensions() > 0 ? "Active Projects" : "No Projects"));
            });
        }
    }

    public static File getTemp() {
        return instance.getDataFolder("cache", "temp");
    }

    public void verifyDataPacksPost() {
        File packs = new File("plugins/Iris/packs");
        File dpacks = getDatapacksFolder();

        if (dpacks == null) {
            Iris.error("Cannot find the datapacks folder! Please try generating a default world first maybe? Is this a new server?");
            return;
        }

        boolean bad = false;
        if (packs.exists()) {
            for (File i : packs.listFiles()) {
                if (i.isDirectory()) {
                    Iris.verbose("Checking Pack: " + i.getPath());
                    IrisData data = new IrisData(i);
                    File dims = new File(i, "dimensions");

                    if (dims.exists()) {
                        for (File j : dims.listFiles()) {
                            if (j.getName().endsWith(".json")) {
                                IrisDimension dim = data.getDimensionLoader().load(j.getName().split("\\Q.\\E")[0]);

                                if (!verifyDataPackInstalled(dim)) {
                                    bad = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (bad && INMS.get().supportsDataPacks()) {
            Iris.error("============================================================================");
            Iris.error(C.ITALIC + "You need to restart your server to properly generate custom biomes.");
            Iris.error(C.ITALIC + "By continuing, Iris will use backup biomes in place of the custom biomes.");
            Iris.error("----------------------------------------------------------------------------");
            Iris.error(C.UNDERLINE + "IT IS HIGHLY RECOMMENDED YOU RESTART THE SERVER BEFORE GENERATING!");
            Iris.error("============================================================================");

            for (Player i : Bukkit.getOnlinePlayers()) {
                if (i.isOp() || Iris.perm.has(i)) {
                    VolmitSender sender = new VolmitSender(i, getTag("WARNING"));
                    sender.sendMessage("There are some Iris Packs that have custom biomes in them");
                    sender.sendMessage("You need to restart your server to use these packs.");
                }
            }
        }
    }

    public boolean verifyDataPackInstalled(IrisDimension dimension) {
        IrisData idm = new IrisData(getDataFolder("packs", dimension.getLoadKey()));
        KSet<String> keys = new KSet<>();
        boolean warn = false;

        for (IrisBiome i : dimension.getAllBiomes(() -> idm)) {
            if (i.isCustom()) {
                for (IrisBiomeCustom j : i.getCustomDerivitives()) {
                    keys.add(dimension.getLoadKey() + ":" + j.getId());
                }
            }
        }

        if (!INMS.get().supportsDataPacks()) {
            if (!keys.isEmpty()) {
                Iris.warn("===================================================================================");
                Iris.warn("Pack " + dimension.getLoadKey() + " has " + keys.size() + " custom biome(s). ");
                Iris.warn("Your server version does not yet support datapacks for iris.");
                Iris.warn("The world will generate these biomes as backup biomes.");
                Iris.warn("====================================================================================");
            }

            return true;
        }

        for (String i : keys) {
            Object o = INMS.get().getCustomBiomeBaseFor(i);

            if (o == null) {
                Iris.warn("The Biome " + i + " is not registered on the server.");
                warn = true;
            }
        }

        if (warn) {
            Iris.error("The Pack " + dimension.getLoadKey() + " is INCAPABLE of generating custom biomes, restart your server before generating with this pack!");
        }

        return !warn;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return super.onCommand(sender, command, label, args);
    }

    public void imsg(CommandSender s, String msg) {
        s.sendMessage(C.IRIS + "[" + C.DARK_GRAY + "Iris" + C.IRIS + "]" + C.GRAY + ": " + msg);
    }


    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {

        IrisDimension dim;
        if (id == null || id.isEmpty()){
            dim = IrisData.loadAnyDimension(IrisSettings.get().getGenerator().getDefaultWorldType());
        } else {
            dim = IrisData.loadAnyDimension(id);
        }
        Iris.info("Generator ID: " + id + " requested by bukkit/plugin");

        if (dim == null) {
            Iris.warn("Unable to find dimension type " + id + " Looking for online packs...");

            Iris.proj.downloadSearch(new VolmitSender(Bukkit.getConsoleSender()), id, true);
            dim = IrisData.loadAnyDimension(id);

            if (dim == null) {
                throw new RuntimeException("Can't find dimension " + id + "!");
            } else {
                Iris.info("Resolved missing dimension, proceeding with generation.");
            }
        }
        Iris.info("Assuming IrisDimension: " + dim.getName());

        IrisWorld w = IrisWorld.builder()
                .name(worldName)
                .seed(RNG.r.lmax())
                .environment(dim.getEnvironment())
                .worldFolder(new File(worldName))
                .minHeight(0)
                .maxHeight(256)
                .build();
        return new BukkitChunkGenerator(w, false, new File(w.worldFolder(), "iris"), dim.getLoadKey());
    }

    public static void msg(String string) {
        try {
            sender.sendMessage(string);
        } catch (Throwable e) {
            try {
                System.out.println(instance.getTag() + string.replaceAll("(<([^>]+)>)", ""));
            } catch (Throwable ignored1) {

            }
        }
    }

    public static File getCached(String name, String url) {
        String h = IO.hash(name + "@" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        if (!f.exists()) {
            try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
                byte[] dataBuffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                    fileOutputStream.write(dataBuffer, 0, bytesRead);
                    Iris.verbose("Aquiring " + name);
                }
            } catch (IOException e) {
                Iris.reportError(e);
            }
        }

        return f.exists() ? f : null;
    }

    public static String getNonCached(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        } catch (IOException e) {
            Iris.reportError(e);
        }

        try {
            return IO.readAll(f);
        } catch (IOException e) {
            Iris.reportError(e);
        }

        return "";
    }

    public static File getNonCachedFile(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = Iris.instance.getDataFile("cache", h.substring(0, 2), h.substring(3, 5), h);
        Iris.verbose("Download " + name + " -> " + url);
        try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream()); FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
            Iris.reportError(e);
        }

        return f;
    }

    public static void warn(String string) {
        msg(C.YELLOW + string);
    }

    public static void error(String string) {
        msg(C.RED + string);
    }

    public static void debug(String string) {
        if (!IrisSettings.get().getGeneral().isDebug()) {
            return;
        }

        try {
            throw new RuntimeException();
        } catch (Throwable e) {
            try {
                String[] cc = e.getStackTrace()[1].getClassName().split("\\Q.\\E");

                if (cc.length > 5) {
                    debug(cc[3] + "/" + cc[4] + "/" + cc[cc.length - 1], e.getStackTrace()[1].getLineNumber(), string);
                } else {
                    debug(cc[3] + "/" + cc[4], e.getStackTrace()[1].getLineNumber(), string);
                }
            } catch (Throwable ex) {
                debug("Origin", -1, string);
            }
        }
    }

    public static void debug(String category, int line, String string) {
        if (!IrisSettings.get().getGeneral().isDebug()) {
            return;
        }

        msg("<gradient:#095fe0:#a848db>" + category + " <#bf3b76>" + line + "<reset> " + C.LIGHT_PURPLE + string.replaceAll("\\Q<\\E", "[").replaceAll("\\Q>\\E", "]"));
    }

    public static void verbose(String string) {
        try {
            if (IrisSettings.get().getGeneral().isVerbose()) {
                msg(C.GRAY + string);
            }
        } catch (Throwable e) {
            msg(C.GRAY + string);
            Iris.reportError(e);
        }
    }

    public static void success(String string) {
        msg(C.IRIS + string);
    }

    public static void info(String string) {
        msg(C.WHITE + string);
    }

    public void hit(long hits2) {
        board.hits.put(hits2);
    }

    public void splash() {
        J.a(this::verifyDataPacksPost, 20);
        if (!IrisSettings.get().getGeneral().isSplashLogoStartup()) {
            return;
        }

        // @NoArgsConstructor
        String padd = Form.repeat(" ", 8);
        String padd2 = Form.repeat(" ", 4);
        String[] info = {"", "", "", "", "", padd2 + C.IRIS + " Iris", padd2 + C.GRAY + " by " + "<rainbow>Volmit Software", padd2 + C.GRAY + " v" + C.IRIS + getDescription().getVersion(),
        };
        String[] splash = {padd + C.GRAY + "   @@@@@@@@@@@@@@" + C.DARK_GRAY + "@@@", padd + C.GRAY + " @@&&&&&&&&&" + C.DARK_GRAY + "&&&&&&" + C.IRIS + "   .(((()))).                     ", padd + C.GRAY + "@@@&&&&&&&&" + C.DARK_GRAY + "&&&&&" + C.IRIS + "  .((((((())))))).                  ", padd + C.GRAY + "@@@&&&&&" + C.DARK_GRAY + "&&&&&&&" + C.IRIS + "  ((((((((()))))))))               " + C.GRAY + " @", padd + C.GRAY + "@@@&&&&" + C.DARK_GRAY + "@@@@@&" + C.IRIS + "    ((((((((-)))))))))              " + C.GRAY + " @@", padd + C.GRAY + "@@@&&" + C.IRIS + "            ((((((({ }))))))))           " + C.GRAY + " &&@@@", padd + C.GRAY + "@@" + C.IRIS + "               ((((((((-)))))))))    " + C.DARK_GRAY + "&@@@@@" + C.GRAY + "&&&&@@@", padd + C.GRAY + "@" + C.IRIS + "                ((((((((()))))))))  " + C.DARK_GRAY + "&&&&&" + C.GRAY + "&&&&&&&@@@", padd + C.GRAY + "" + C.IRIS + "                  '((((((()))))))'  " + C.DARK_GRAY + "&&&&&" + C.GRAY + "&&&&&&&&@@@", padd + C.GRAY + "" + C.IRIS + "                     '(((())))'   " + C.DARK_GRAY + "&&&&&&&&" + C.GRAY + "&&&&&&&@@", padd + C.GRAY + "                               " + C.DARK_GRAY + "@@@" + C.GRAY + "@@@@@@@@@@@@@@"
        };
        //@done
        Iris.info("Server type & version: " + Bukkit.getVersion());
        Iris.info("Bukkit version: " + Bukkit.getBukkitVersion());
        Iris.info("Java version: " + getJavaVersion());
        Iris.info("Custom Biomes: " + INMS.get().countCustomBiomes());
        for (int i = 0; i < info.length; i++) {
            splash[i] += info[i];
        }

        Iris.info("\n\n " + new KList<>(splash).toString("\n") + "\n");
    }

    @SuppressWarnings("deprecation")
    public static void later(NastyRunnable object) {
        Bukkit.getScheduler().scheduleAsyncDelayedTask(instance, () ->
        {
            try {
                object.run();
            } catch (Throwable e) {
                e.printStackTrace();
                Iris.reportError(e);
            }
        }, RNG.r.i(100, 1200));
    }

    public static int jobCount() {
        return syncJobs.size();
    }

    public static void clearQueues() {
        synchronized (syncJobs) {
            syncJobs.clear();
        }
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    public boolean isMCA() {
        return !IrisSettings.get().getGenerator().isDisableMCA();
    }

    public static void reportErrorChunk(int x, int z, Throwable e, String extra) {
        if (IrisSettings.get().getGeneral().isDebug()) {
            File f = instance.getDataFile("debug", "chunk-errors", "chunk." + x + "." + z + ".txt");

            if (!f.exists()) {
                J.attempt(() -> {
                    PrintWriter pw = new PrintWriter(f);
                    pw.println("Thread: " + Thread.currentThread().getName());
                    pw.println("First: " + new Date(M.ms()));
                    e.printStackTrace(pw);
                    pw.close();
                });
            }

            Iris.debug("Chunk " + x + "," + z + " Exception Logged: " + e.getClass().getSimpleName() + ": " + C.RESET + "" + C.LIGHT_PURPLE + e.getMessage());
        }
    }

    public static synchronized void reportError(Throwable e) {
        if (IrisSettings.get().getGeneral().isDebug()) {
            String n = e.getClass().getCanonicalName() + "-" + e.getStackTrace()[0].getClassName() + "-" + e.getStackTrace()[0].getLineNumber();

            if (e.getCause() != null) {
                n += "-" + e.getCause().getStackTrace()[0].getClassName() + "-" + e.getCause().getStackTrace()[0].getLineNumber();
            }

            File f = instance.getDataFile("debug", "caught-exceptions", n + ".txt");

            if (!f.exists()) {
                J.attempt(() -> {
                    PrintWriter pw = new PrintWriter(f);
                    pw.println("Thread: " + Thread.currentThread().getName());
                    pw.println("First: " + new Date(M.ms()));
                    e.printStackTrace(pw);
                    pw.close();
                });
            }

            Iris.debug("Exception Logged: " + e.getClass().getSimpleName() + ": " + C.RESET + "" + C.LIGHT_PURPLE + e.getMessage());
        }
    }

    static {
        try {
            InstanceState.updateInstanceId();
        } catch (Throwable ignored) {

        }
    }
}
