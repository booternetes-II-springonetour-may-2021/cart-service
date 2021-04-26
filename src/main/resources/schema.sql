create  table if not exists   cafe
(
    id   serial primary key,
    name varchar(255) not null
);


create table if not exists  cafe_orders
(
    id       serial primary key,
    username varchar(255) not null,
    quantity int,
    coffee   varchar(255) not null
);