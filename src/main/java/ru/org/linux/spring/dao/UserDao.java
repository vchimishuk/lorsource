package ru.org.linux.spring.dao;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import org.jasypt.util.password.BasicPasswordEncryptor;
import org.jasypt.util.password.PasswordEncryptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.org.linux.site.*;
import ru.org.linux.util.StringUtil;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class UserDao {
  private JdbcTemplate jdbcTemplate;

  /**
   * изменение score пользователю
   */
  private final static String queryChangeScore = "UPDATE users SET score=score+? WHERE id=?";

  private final static String queryNewUsers = "SELECT id FROM users where " +
                                                "regdate IS NOT null " +
                                                "AND regdate > CURRENT_TIMESTAMP - interval '3 days' " +
                                              "ORDER BY regdate";

  private final static String queryUserInfoClass = "SELECT url, town, lastlogin, regdate FROM users WHERE id=?";
  private final static String queryBanInfoClass = "SELECT * FROM ban_info WHERE userid=?";

  private final static String queryIgnoreStat = "SELECT count(*) as inum FROM ignore_list JOIN users ON  ignore_list.userid = users.id WHERE ignored=? AND not blocked";
  private final static String queryCommentStat = "SELECT count(*) as c FROM comments WHERE userid=? AND not deleted";
  private final static String queryTopicDates = "SELECT min(postdate) as first,max(postdate) as last FROM topics WHERE topics.userid=?";
  private final static String queryCommentDates = "SELECT min(postdate) as first,max(postdate) as last FROM comments WHERE comments.userid=?";
  private final static String queryCommentsBySectionStat =
            "SELECT sections.name as pname, count(*) as c " +
                    "FROM topics, groups, sections " +
                    "WHERE topics.userid=? " +
                    "AND groups.id=topics.groupid " +
                    "AND sections.id=groups.section " +
                    "AND not deleted " +
                    "GROUP BY sections.name";

  private final static String queryIgnoreList = "SELECT a.ignored,b.nick FROM ignore_list a, users b WHERE a.userid=? AND b.id=a.ignored";

  @Autowired
  public void setJdbcTemplate(DataSource dataSource) {
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Deprecated
  public static User getUser(JdbcTemplate jdbcTemplate, String nick) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, nick);
  }

  private static User getUserInternal(JdbcTemplate jdbcTemplate, String nick) throws UserNotFoundException {
    if (nick == null) {
      throw new NullPointerException();
    }

    if (!StringUtil.checkLoginName(nick)) {
      throw new UserNotFoundException("<invalid name>");
    }

    Cache cache = CacheManager.create().getCache("Users");

    List<User> list = jdbcTemplate.query(
            "SELECT id,nick,candel,canmod,corrector,passwd,blocked,score,max_score,activated,photo,email,name,unread_events FROM users where nick=?",
            new RowMapper<User>() {
              @Override
              public User mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new User(rs);
              }
            },
            nick
    );

    if (list.isEmpty()) {
      throw new UserNotFoundException(nick);
    }

    if (list.size()>1) {
      throw new RuntimeException("list.size()>1 ???");
    }

    User user = list.get(0);

    String cacheId = "User?id="+ user.getId();

    cache.put(new Element(cacheId, user));

    return user;
  }

  public User getUser(String nick) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, nick);
  }

  public User getUser(int id, boolean useCache) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, id, useCache);
  }

  public User getUserCached(int id) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, id, true);
  }

  public User getUser(int id) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, id, false);
  }

  @Deprecated
  public static User getUser(JdbcTemplate jdbcTemplate, int id, boolean useCache) throws UserNotFoundException {
    return getUserInternal(jdbcTemplate, id, useCache);
  }

  private static User getUserInternal(JdbcTemplate jdbcTemplate, int id, boolean useCache) throws UserNotFoundException {
    Cache cache = CacheManager.create().getCache("Users");

    String cacheId = "User?id="+id;

    User res = null;

    if (useCache) {
      Element element = cache.get(cacheId);

      if (element!=null) {
        res = (User) element.getObjectValue();
      }
    }

    if (res==null) {
      List<User> list = jdbcTemplate.query(
                "SELECT id, nick,score, max_score, candel,canmod,corrector,passwd,blocked,activated,photo,email,name,unread_events FROM users where id=?",              new RowMapper<User>() {
                @Override
                public User mapRow(ResultSet rs, int rowNum) throws SQLException {
                  return new User(rs);
                }
              },
              id
      );

      if (list.isEmpty()) {
        throw new UserNotFoundException(id);
      }

      if (list.size()>1) {
        throw new RuntimeException("list.size()>1 ???");
      }

      res = list.get(0);

      cache.put(new Element(cacheId, res));
    }

    return res;
  }

  /**
   * Получить поле userinfo пользователя
   * TODO надо переименовать?
   * @param user пользователь
   * @return поле userinfo
   */
  public String getUserInfo(User user) {
    return jdbcTemplate.queryForObject("SELECT userinfo FROM users where id=?",
        new Object[] {user.getId()}, String.class);
  }

  /**
   * Получить информацию о пользователе
   * @param user пользователь
   * @return информация
   */
  public UserInfo getUserInfoClass(User user) {
    return jdbcTemplate.queryForObject(queryUserInfoClass, new RowMapper<UserInfo>() {
      @Override
      public UserInfo mapRow(ResultSet resultSet, int i) throws SQLException {
        return new UserInfo(resultSet);
      }
    }, user.getId());
  }

  /**
   * Получить информацию о бане
   * @param user пользователь
   * @return информация о бане :-)
   */
  public BanInfo getBanInfoClass(User user) {
    return jdbcTemplate.queryForObject(queryBanInfoClass, new RowMapper<BanInfo>() {
      @Override
      public BanInfo mapRow(ResultSet resultSet, int i) throws SQLException {
        Timestamp date = resultSet.getTimestamp("bandate");
        String reason = resultSet.getString("reason");
        User moderator;
        try {
          moderator = getUser(resultSet.getInt("ban_by"));
        } catch (UserNotFoundException exception) {
          throw new SQLException(exception.getMessage());
        }
        return new BanInfo(date, reason, moderator);
      }
    }, user.getId());
  }

  /**
   * Получить статситику пользователя
   * @param user пользователь
   * @return статистика
   */
  public UserStatistics getUserStatisticsClass(User user) {
    int ignoreCount;
    int commentCount;
    List<Timestamp> commentStat;
    List<Timestamp> topicStat;
    Map<String, Integer> commentsBySection;
    try {
      ignoreCount = jdbcTemplate.queryForInt(queryIgnoreStat, user.getId());
    } catch (EmptyResultDataAccessException exception) {
      ignoreCount = 0;
    }
    try {
      commentCount = jdbcTemplate.queryForInt(queryCommentStat, user.getId());
    } catch (EmptyResultDataAccessException exception) {
      commentCount = 0;
    }

    try {
      commentStat = jdbcTemplate.queryForObject(queryCommentDates, new RowMapper<List<Timestamp>>() {
        @Override
        public List<Timestamp> mapRow(ResultSet resultSet, int i) throws SQLException {
          return ImmutableList.of(resultSet.getTimestamp("first"), resultSet.getTimestamp("last"));
        }
      }, user.getId());
    } catch (EmptyResultDataAccessException exception) {
      commentStat = null;
    }

    try {
      topicStat = jdbcTemplate.queryForObject(queryTopicDates, new RowMapper<List<Timestamp>>() {
        @Override
        public List<Timestamp> mapRow(ResultSet resultSet, int i) throws SQLException {
          return Lists.newArrayList(resultSet.getTimestamp("first"), resultSet.getTimestamp("last"));
        }
      }, user.getId());
    } catch (EmptyResultDataAccessException exception) {
      topicStat = null;
    }

    final ImmutableMap.Builder<String, Integer> builder = ImmutableMap.builder();
    jdbcTemplate.query(queryCommentsBySectionStat, new RowCallbackHandler() {
      @Override
      public void processRow(ResultSet resultSet) throws SQLException {
        builder.put(resultSet.getString("pname"), resultSet.getInt("c"));
      }
    }, user.getId());
    return new UserStatistics(ignoreCount, commentCount,
        commentStat.get(0), commentStat.get(1),
        topicStat.get(0), topicStat.get(1),
        builder.build());
  }

  /**
   * Получить список игнорируемых
   * @param user пользователь который игнорирует
   * @return список игнорируемых
   */
  public Map<Integer, String> getIgnoreList(User user) {
    final ImmutableMap.Builder<Integer, String> builder = ImmutableMap.builder();
    jdbcTemplate.query(queryIgnoreList, new RowCallbackHandler() {
      @Override
      public void processRow(ResultSet resultSet) throws SQLException {
        builder.put(resultSet.getInt("ignored"),resultSet.getString("nick"));
      }
    }, user.getId());
    return builder.build();
  }

  /**
   * Получить список новых пользователей зарегистрирововавшихся за последние 3(три) дня
   * @return список новых пользователей
   */
  public List<User> getNewUsers() {
    return jdbcTemplate.query(queryNewUsers, new RowMapper<User>() {
      @Override
      public User mapRow(ResultSet resultSet, int i) throws SQLException {
        try {
          return getUser(resultSet.getInt("id"));
        } catch (UserNotFoundException e) {
          throw new SQLException(e.getMessage());
        }
      }
    });
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void removeUserInfo(User user) {
    String userInfo = getUserInfo(user);
    if(userInfo == null || userInfo.trim().isEmpty()) {
      return;
    }
    setUserInfo(user, null);
    changeScore(user.getId(), -10);
  }

  /**
   * Отчистка userpicture пользователя, с обрезанием шкворца если удляет моедратор
   * @param user пользовтель у которого чистят
   * @param cleaner пользователь который чистит
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void removePhoto(User user, User cleaner) {
    setPhoto(user, null);
    if(cleaner.canModerate() && cleaner.getId() != user.getId()){
      changeScore(user.getId(), -10);
    }
  }

  /**
   * Обновление userpic-а пользовтаеля
   * @param user пользователь
   * @param photo userpick
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void setPhoto(User user, String photo){
    jdbcTemplate.update("UPDATE users SET photo=? WHERE id=?", photo, user.getId());
  }

  /**
   * Обновление дополнительной информации пользователя
   * @param user пользователь
   * @param text текст дополнительной информации
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void setUserInfo(User user, String text){
    jdbcTemplate.update("UPDATE users SET userinfo=? where id=?", text, user.getId());
  }

  /**
   * Изменение шкворца пользовтаеля, принимает отрицательные и положительные значения
   * не накладывает никаких ограничений на параметры используется в купэ с другими
   * методами и не является транзакцией
   * @param id id пользователя
   * @param delta дельта на которую меняется шкворец
   */
  public void changeScore(int id, int delta) {
    if (jdbcTemplate.update(queryChangeScore, delta, id)==0) {
      throw new IllegalArgumentException(new UserNotFoundException(id));
    }

    updateCache(id);
  }

  /**
   * Смена признака корректора для пользователя
   * @param user пользователь у которого меняется признак корректора
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void toggleCorrector(User user){
    if(user.canCorrect()){
      jdbcTemplate.update("UPDATE users SET corrector='f' WHERE id=?", user.getId());
    }else{
      jdbcTemplate.update("UPDATE users SET corrector='t' WHERE id=?", user.getId());
    }
  }

  /**
   * Смена пароля пользователю
   * @param user пользователь которому меняется пароль
   * @param password пароль в открытом виде
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void setPassword(User user, String password){
    setPasswordWithoutTransaction(user, password);
  }

  public void setPasswordWithoutTransaction(User user, String password) {
    PasswordEncryptor encryptor = new BasicPasswordEncryptor();
    String encryptedPassword = encryptor.encryptPassword(password);
    jdbcTemplate.update("UPDATE users SET passwd=?, lostpwd = 'epoch' WHERE id=?",
        encryptedPassword, user.getId());
  }

  /**
   * Сброс пороля на случайный
   * @param user пользователь которому сбрасывается пароль
   * @return новый пароь в открытом виде
   */
  public String resetPassword(User user){
    String password = StringUtil.generatePassword();
    setPassword(user, password);
    return password;
  }

  public String resetPasswordWithoutTransaction(User user) {
    String password = StringUtil.generatePassword();
    setPasswordWithoutTransaction(user, password);
    return password;
  }

  /**
   * Блокирование пользователя без транзакации(используется в CommentDao для массового удаления с блокировкой)
   * @param user пользователь которого блокируем
   * @param moderator модератор который блокирует
   * @param reason причина блокировки
   */
  public void blockWithoutTransaction(User user, User moderator, String reason) {
    jdbcTemplate.update("UPDATE users SET blocked='t' WHERE id=?", user.getId());
    jdbcTemplate.update("INSERT INTO ban_info (userid, reason, ban_by) VALUES (?, ?, ?)",
        user.getId(), reason, moderator.getId());
    updateCache(user.getId());
  }

  private void updateCache(int id) {
    try {
      getUser(id);
    } catch (UserNotFoundException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Блокировка пользователя и сброс пароля одной транзикацией
   * @param user блокируемый пользователь
   * @param moderator модератор который блокирует пользователя
   * @param reason причина блокировки
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void blockWithResetPassword(User user, User moderator, String reason) {

    jdbcTemplate.update("UPDATE users SET blocked='t' WHERE id=?", user.getId());
    jdbcTemplate.update("INSERT INTO ban_info (userid, reason, ban_by) VALUES (?, ?, ?)",
        user.getId(), reason, moderator.getId());
    PasswordEncryptor encryptor = new BasicPasswordEncryptor();
    String password = encryptor.encryptPassword(StringUtil.generatePassword());
    jdbcTemplate.update("UPDATE users SET passwd=?, lostpwd = 'epoch' WHERE id=?",
        password, user.getId());
    updateCache(user.getId());
  }


  /**
   * Разблокировка пользователя
   * @param user разблокируемый пользователь
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void unblock(User user){
    jdbcTemplate.update("UPDATE users SET blocked='f' WHERE id=?", user.getId());
    jdbcTemplate.update("DELETE FROM ban_info WHERE userid=?", user.getId());
  }

  public User getAnonymous() {
    try {
      return getUser(2);
    } catch (UserNotFoundException e) {
      throw new RuntimeException("Anonymous not found!?", e);
    }
  }
}
