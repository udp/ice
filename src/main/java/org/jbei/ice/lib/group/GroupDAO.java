package org.jbei.ice.lib.group;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jbei.ice.lib.account.model.Account;
import org.jbei.ice.lib.dao.DAOException;
import org.jbei.ice.lib.dao.hibernate.HibernateRepository;
import org.jbei.ice.lib.logging.Logger;
import org.jbei.ice.lib.shared.dto.group.GroupType;

import org.hibernate.Criteria;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;

/**
 * Manager to manipulate {@link Group} objects.
 *
 * @author Timothy Ham, Zinovii Dmytriv, Hector Plahar
 */
@SuppressWarnings("unchecked")
class GroupDAO extends HibernateRepository<Group> {
    /**
     * Retrieve {@link Group} object from the database by its uuid.
     *
     * @param uuid universally unique identifier for group
     * @return Group object.
     * @throws DAOException
     */
    public Group get(String uuid) throws DAOException {
        return super.getByUUID(Group.class, uuid);
    }

    public long getMemberCount(String uuid) throws DAOException {
        return get(uuid).getMembers().size();
    }

    /**
     * Retrieve {@link Group} object from the database by its id.
     *
     * @param id group unique identifier
     * @return Group object.
     * @throws DAOException
     */
    public Group get(long id) throws DAOException {
        return super.get(Group.class, id);
    }

    public HashSet<Group> getByIdList(Set<Long> idsSet) throws DAOException {
        Session session = currentSession();

        try {
            Criteria criteria = session.createCriteria(Group.class).add(Restrictions.in("id", idsSet));
            List list = criteria.list();
            return new HashSet<Group>(list);

        } catch (HibernateException he) {
            Logger.error(he);
            throw new DAOException(he);
        }
    }

    public Set<Group> getMatchingGroups(Account account, String token, int limit) throws DAOException {
        Session session = currentSession();
        Set<Group> userGroups = account.getGroups();

        try {
            token = token.toUpperCase();
            // match the string and group must either be public, owned by user or in user's private groups
            String queryString = "from " + Group.class.getName() + " g where (UPPER(g.label) like '%" + token + "%')";
            Query query = session.createQuery(queryString);

            if (limit > 0)
                query.setMaxResults(limit);

            @SuppressWarnings("unchecked")
            HashSet<Group> result = new HashSet<Group>(query.list());
            if (result == null || result.isEmpty())
                return result;

            HashSet<Group> matches = new HashSet<>();
            for (Group group : result) {
                if (group.getUuid().equals(GroupController.PUBLIC_GROUP_UUID))
                    continue;

                if (group.getType() != GroupType.PUBLIC && (userGroups == null || !userGroups.contains(group)))
                    continue;

                matches.add(group);
            }

            return matches;
        } catch (HibernateException e) {
            Logger.error(e);
            throw new DAOException("Error retrieving matching groups", e);
        }
    }

    public Set<Group> retrieveMemberGroups(Account account) throws DAOException {
        Criteria criteria = currentSession().createCriteria(Group.class);
        criteria.createAlias("members", "m");
        criteria.add(Restrictions.or(
                Restrictions.eq("owner", account),
                Restrictions.eq("m.email", account.getEmail())));
        List list = criteria.list();
        return new HashSet<Group>(list);
    }

    public ArrayList<Group> retrieveGroups(Account account, GroupType type) throws DAOException {
        Session session = currentSession();
        Criteria criteria = session.createCriteria(Group.class);
        if (type != null) {
            criteria = criteria.add(Restrictions.eq("type", type));
        }

        criteria.add(Restrictions.eq("owner", account));
        List result = criteria.list();
        return new ArrayList<Group>(result);
    }

    public ArrayList<Group> retrievePublicGroups() throws DAOException {
        Session session = currentSession();
        Criteria criteria = session.createCriteria(Group.class);
        criteria = criteria.add(Restrictions.eq("type", GroupType.PUBLIC));
        List result = criteria.list();
        return new ArrayList<Group>(result);
    }

    public List<Group> getAutoJoinGroups() throws DAOException {
        Session session = currentSession();
        Criteria criteria = session.createCriteria(Group.class);
        criteria = criteria.add(Restrictions.eq("type", GroupType.PUBLIC));
        criteria.add(Restrictions.conjunction()
                                 .add(Restrictions.isNotNull("autoJoin"))
                                 .add(Restrictions.eq("autoJoin", Boolean.TRUE)));

        try {
            List result = criteria.list();
            return new ArrayList<Group>(result);
        } catch (HibernateException he) {
            Logger.error(he);
            throw new DAOException();
        }
    }
}
