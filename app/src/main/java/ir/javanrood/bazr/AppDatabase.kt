package ir.javanrood.bazr

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="missions")
data class MissionEntity(@PrimaryKey val key:String,val title:String,val date:String,val type:String,val payload:String,val updatedAt:String,val submitted:Boolean=false)
@Entity(tableName="drafts")
data class DraftEntity(@PrimaryKey val missionKey:String,val payload:String,val updatedAt:Long=System.currentTimeMillis(),val finalPending:Boolean=false)
@Entity(tableName="reports")
data class ReportEntity(
    @PrimaryKey val missionKey:String,
    val title:String,
    val date:String,
    val type:String,
    val receipt:String,
    val pdfPath:String="",
    val submittedAt:Long=System.currentTimeMillis(),
    val status:String="submitted"
)

@Dao interface MissionDao{
 @Query("SELECT * FROM missions ORDER BY date DESC") fun observe():Flow<List<MissionEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putAll(x:List<MissionEntity>)
 @Query("UPDATE missions SET submitted=1 WHERE `key`=:key") suspend fun markSubmitted(key:String)
 @Query("SELECT `key` FROM missions WHERE submitted=1") suspend fun submittedKeys():List<String>
}
@Dao interface DraftDao{
 @Query("SELECT * FROM drafts WHERE missionKey=:key LIMIT 1") suspend fun get(key:String):DraftEntity?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(x:DraftEntity)
 @Query("DELETE FROM drafts WHERE missionKey=:key") suspend fun remove(key:String)
}
@Dao interface ReportDao{
 @Query("SELECT * FROM reports ORDER BY submittedAt DESC") fun observe():Flow<List<ReportEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(x:ReportEntity)
}

@Database(entities=[MissionEntity::class,DraftEntity::class,ReportEntity::class],version=2,exportSchema=false)
abstract class BazrDb:RoomDatabase(){
 abstract fun missions():MissionDao
 abstract fun drafts():DraftDao
 abstract fun reports():ReportDao
 companion object{
  @Volatile private var I:BazrDb?=null
  private val MIGRATION_1_2 = object: Migration(1,2){
   override fun migrate(db: SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE IF NOT EXISTS `reports` (`missionKey` TEXT NOT NULL, `title` TEXT NOT NULL, `date` TEXT NOT NULL, `type` TEXT NOT NULL, `receipt` TEXT NOT NULL, `pdfPath` TEXT NOT NULL, `submittedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`missionKey`))")
   }
  }
  fun get(c:Context)=I?:synchronized(this){I?:Room.databaseBuilder(c.applicationContext,BazrDb::class.java,"bazr_mobile.db").addMigrations(MIGRATION_1_2).build().also{I=it}}
 }
}
