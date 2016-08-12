package ru.redenergy.report.server.backend

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.field.DataPersisterManager
import com.j256.ormlite.field.DatabaseFieldConfig
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ChatComponentText
import net.minecraft.util.ChatComponentTranslation
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.IChatComponent
import ru.redenergy.report.common.Stats
import ru.redenergy.report.common.TicketReason
import ru.redenergy.report.common.TicketStatus
import ru.redenergy.report.common.entity.*
import ru.redenergy.report.common.network.NetworkHandler
import ru.redenergy.report.common.network.packet.SyncStatsPackets
import ru.redenergy.report.common.network.packet.SyncTickets
import ru.redenergy.report.common.network.packet.UpdateAdminAccess
import ru.redenergy.report.server.QReportServer
import ru.redenergy.report.server.orm.JsonPersister
import ru.redenergy.vault.ForgeVault

class ReportManager(val connectionSource: ConnectionSource) {

    init{
        DataPersisterManager.registerDataPersisters(JsonPersister.getSingleton())
    }

    /**
     * ORM configuration for Ticket.class
     */
    val ticketConfig = configureTicketEntity()
    /**
     * ORM Dao for Ticket.class
     */
    var ticketDao = DaoManager.createDao<Dao<Ticket, Int>, Ticket>(connectionSource, ticketConfig)

    /**
     * Contains tickets from last database query <br>
     * May be empty if no database queries has been performed
     */
    var cachedTickets: MutableList<Ticket> = arrayListOf()

    fun initialize() {
        TableUtils.createTableIfNotExists(connectionSource, Ticket::class.java)
    }

    /**
     * Stores ticket in database
     */
    fun addTicket(ticket: Ticket) = ticketDao.create(ticket)

    /**
     * Deletes given ticket from database
     */
    fun deleteTicket(ticket: Ticket) = ticketDao.delete(ticket)

    /**
     * Queries all tickets from database <br>
     * Replaces tickets cache with obtained from database <br>
     * Returns cache
     */
    fun getTickets(): MutableList<Ticket> {
        cachedTickets.clear()
        cachedTickets.addAll(ticketDao.queryForAll())
        return cachedTickets
    }

    /**
     * Returns all tickets sent by player with the given name
     */
    fun getTicketsByPlayer(player: String): MutableList<Ticket> =
        ticketDao.queryBuilder().where().eq("sender", player).query()

    /**
     * Creates new ticket and stores it's in database
     * @parama text - text for a first message
     * @param reason - reason of a ticket
     * @param sender - name of a sender
     */
    fun newTicket(text: String, reason: TicketReason, sender: String): Ticket =
        Ticket(-1, TicketStatus.OPEN, sender, reason, arrayListOf(TicketMessage(sender, text)), QReportServer.server)
                .apply { addTicket(this) }

    fun gatherStats(): Stats {
        val tickets = getTickets()
        val countTickets = tickets.countReasons()
        val activeUsers = tickets.activeUsers(5)
        val averageResponseTime = tickets.averageResponseTime()
        return Stats(countTickets, activeUsers, averageResponseTime)
    }

    /**
     * Returns ORM configuration for Ticket.class
     */
    private fun configureTicketEntity(): DatabaseTableConfig<Ticket> {
        return DatabaseTableConfig(Ticket::class.java, arrayListOf<DatabaseFieldConfig>().apply {
            add(DatabaseFieldConfig("uid").apply { isGeneratedId = true;})
            add(DatabaseFieldConfig("status"))
            add(DatabaseFieldConfig("sender"))
            add(DatabaseFieldConfig("server"))
            add(DatabaseFieldConfig("reason"))
            add(DatabaseFieldConfig("messages").apply { persisterClass = JsonPersister::class.java })
        }).apply { tableName = "tickets" }
    }

    /**
     * Checks if player have permission to answer/close this particular ticket
     */
    private fun canAccessTicket(ticket: Ticket, player: EntityPlayerMP): Boolean{
        return ticket.sender.equals(player.commandSenderName, true) || canAccessTicketManagement(player)
    }

    /**
     * Checks if player have permission to answer/close other players tickets
     */
    private fun canAccessTicketManagement(player: EntityPlayerMP): Boolean {
        if(MinecraftServer.getServer().isDedicatedServer){
            if(QReportServer.checkPermission){
                return ForgeVault.getPermission()?.has(null as String?, player.commandSenderName, QReportServer.permissionNode) ?: false
            } else {
                return isOp(player)
            }
        } else {
            return player.capabilities.isCreativeMode
        }

    }

    /**
     * Returns true if given player has operator permission
     */
    private fun isOp(player: EntityPlayerMP): Boolean = MinecraftServer.getServer().configurationManager
            .func_152603_m().func_152700_a(player.commandSenderName) != null

    /**
     * Returns list with names of users who sent messages to given ticket
     */
    private fun getParticipants(ticket: Ticket): MutableList<String>{
        return hashSetOf(*ticket.messages.map { it.sender }.toTypedArray()).toMutableList()
    }

    /**
     * Sends message to all players with the names in the list <br>
     * If any of players is offline he won't receive message
     */
    private fun notifyUsers(users: List<String>, message: IChatComponent): Unit =
        users.map { MinecraftServer.getServer().configurationManager.func_152612_a(it) }
                .filter { it != null }
                .forEach { it.addChatMessage(message) }

    fun handleUpdateTicketStatus(ticketUid: Int, status: TicketStatus, player: EntityPlayerMP){
        var ticket = ticketDao.queryForId(ticketUid) ?: return
        if(canAccessTicket(ticket, player)) {
            ticket.status = status
            ticketDao.update(ticket)
            if(QReportServer.notifications) {
                val notified = getParticipants(ticket).apply { remove(player.commandSenderName) }
                val message = ChatComponentTranslation("chat.messages.update.status", player.displayName, ticket.shortUid, ticket.status).apply { chatStyle.color = EnumChatFormatting.GOLD }
                notifyUsers(notified, message)
            }
        } else {
            player.addChatMessage(ChatComponentText("${EnumChatFormatting.RED}Ooops, you don't have access to ticket with id ${ticket.shortUid}."))
        }
    }

    fun handleSyncRequest(player: EntityPlayerMP) {
        val adminAccess = canAccessTicketManagement(player)
        NetworkHandler.sendTo(UpdateAdminAccess(adminAccess), player)

        if(adminAccess)
            NetworkHandler.sendTo(SyncStatsPackets(gatherStats()), player)

        var tickets = if (adminAccess)
                          getTickets()
                      else
                          getTicketsByPlayer(player.commandSenderName)

        NetworkHandler.sendTo(SyncTickets(tickets), player)
    }

    fun handleAddMessage(ticketUid: Int, text: String, player: EntityPlayerMP) {
        var ticket = ticketDao.queryForId(ticketUid)?: return
        if(canAccessTicket(ticket, player)){
            var ticketMessage = TicketMessage(player.commandSenderName, text)
            ticket.messages.add(ticketMessage)
            ticketDao.update(ticket)
            if(QReportServer.notifications) {
                val notified = getParticipants(ticket).apply { remove(player.commandSenderName) }
                val message = ChatComponentTranslation("chat.messages.add.message", player.displayName, ticket.shortUid).apply { chatStyle.color = EnumChatFormatting.GOLD }
                notifyUsers(notified, message)
            }
        } else {
            player.addChatMessage(ChatComponentText("${EnumChatFormatting.RED}Ooops, you don't have access to ticket with id ${ticket.shortUid}."))
        }
    }

    fun handleNewTicket(text: String, reason: TicketReason, player: EntityPlayerMP) {
        if(text.isBlank()) return
        val ticket = newTicket(text, reason, player.commandSenderName)
        if(QReportServer.notifications)
            notifyUsers((MinecraftServer.getServer().configurationManager.playerEntityList as MutableList<EntityPlayerMP>)
                    .map {it.commandSenderName}
                    .filter { ForgeVault.getPermission().has(null as String?, it, QReportServer.permissionNode)},
                    ChatComponentTranslation("chat.messages.add.ticket", player.commandSenderName, ticket.uid))

    }

    fun handleDeleteTicker(id: Int, player: EntityPlayerMP){
        var ticket = ticketDao.queryForId(id) ?: return
        if(canAccessTicket(ticket, player)){
            deleteTicket(ticket)
            if(QReportServer.notifications){
                val notified = getParticipants(ticket).apply { remove(player.commandSenderName) }
                val message = ChatComponentTranslation("chat.messages.delete.ticket", player.displayName, ticket.shortUid).apply { chatStyle.color = EnumChatFormatting.GOLD }
                notifyUsers(notified, message)
            }
        } else {
            player.addChatMessage(ChatComponentText("${EnumChatFormatting.RED}Ooops, you don't have access to ticket with id ${ticket.shortUid}."))
        }
    }
}