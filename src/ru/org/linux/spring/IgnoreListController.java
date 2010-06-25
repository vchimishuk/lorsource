/*
 * Copyright 1998-2010 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.spring;

import java.sql.Connection;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import ru.org.linux.site.*;

@Controller
public class IgnoreListController {
  @RequestMapping(value="/ignore-list.jsp", method= RequestMethod.GET)
  public ModelAndView showList(HttpServletRequest request) throws Exception {
    Template tmpl = Template.getTemplate(request);

    if (!tmpl.isSessionAuthorized()) {
      throw new AccessViolationException("Not authorized");
    }

    Connection db = null;
    try {
      db = LorDataSource.getConnection();
      User user = Template.getCurrentUser(db, request.getSession());
      user.checkAnonymous();

      IgnoreList ignore = new IgnoreList(db, user.getId());
      Map<Integer,String> ignoreList = ignore.getIgnoreList();

      return new ModelAndView("ignore-list", "ignoreList", ignoreList);
    } finally {
      if (db!=null) {
        db.close();
      }
    }

  }

  @RequestMapping(value="/ignore-list.jsp", method= RequestMethod.POST, params = "add")
  public ModelAndView listAdd(
    HttpServletRequest request,
    @RequestParam String nick
  ) throws Exception {
    if (!Template.isSessionAuthorized(request.getSession())) {
      throw new AccessViolationException("Not authorized");
    }

    Connection db = null;
    try {
      db = LorDataSource.getConnection();
      db.setAutoCommit(false);
      User user = Template.getCurrentUser(db, request.getSession());
      user.checkAnonymous();

      IgnoreList ignoreList = new IgnoreList(db, user.getId());

      User addUser = User.getUser(db, nick);

      // Add nick to ignore list
      if (nick.equals(user.getNick())) {
        throw new BadInputException("нельзя игнорировать самого себя");
      }

      if (!ignoreList.containsUser(addUser)) {
        ignoreList.addUser(db, addUser);
      }

      db.commit();

      return new ModelAndView("ignore-list", "ignoreList", ignoreList.getIgnoreList());
    } finally {
      if (db!=null) {
        db.close();
      }
    }
  }

  @RequestMapping(value="/ignore-list.jsp", method= RequestMethod.POST, params = "del")
  public ModelAndView listDel(
    HttpServletRequest request,
    @RequestParam int id
  ) throws Exception {
    if (!Template.isSessionAuthorized(request.getSession())) {
      throw new AccessViolationException("Not authorized");
    }

    Connection db = null;
    try {
      db = LorDataSource.getConnection();
      db.setAutoCommit(false);
      User user = Template.getCurrentUser(db, request.getSession());
      user.checkAnonymous();

      IgnoreList ignoreList = new IgnoreList(db, user.getId());

      if (!ignoreList.remove(db, id)) {
        throw new BadInputException("неверный ник");
      }

      db.commit();

      return new ModelAndView("ignore-list", "ignoreList", ignoreList.getIgnoreList());
    } finally {
      if (db!=null) {
        db.close();
      }
    }
  }
}
